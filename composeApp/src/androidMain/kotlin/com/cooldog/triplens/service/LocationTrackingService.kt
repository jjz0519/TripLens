package com.cooldog.triplens.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.db.DatabaseDriverFactory
import com.cooldog.triplens.domain.TransportClassifier
import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.platform.LocationData
import com.cooldog.triplens.platform.LocationProvider
import com.cooldog.triplens.repository.TrackPointInsert
import com.cooldog.triplens.repository.TrackPointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "TripLens/LocationService"

private const val CHANNEL_ID      = "triplens_recording"
private const val NOTIFICATION_ID = 1001
private const val PREFS_NAME      = "triplens_service"
private const val PREFS_SESSION_ID = "active_session_id"
private const val PREFS_PROFILE    = "accuracy_profile"
private const val BUFFER_FLUSH_SIZE = 10

// Auto-pause: enter after this many consecutive milliseconds below STATIONARY_SPEED_KMH.
private const val AUTO_PAUSE_THRESHOLD_MS = 3 * 60 * 60 * 1000L  // 3 hours
// Reduced polling interval during auto-pause (TDD §4.3).
private const val AUTO_PAUSE_INTERVAL_MS  = 5 * 60 * 1000L       // 5 minutes
// Speed below which the device is considered stationary.
private const val STATIONARY_SPEED_KMH   = 1.0f

/**
 * ForegroundService that owns the GPS recording lifecycle.
 *
 * ## Intent API
 * Start recording:  ACTION_START with extras session_id (String) + accuracy_profile (String)
 * Stop recording:   ACTION_STOP  (no extras needed)
 *
 * ## Recovery (START_STICKY)
 * On a system restart the OS delivers onStartCommand with a null intent. The service reads
 * session_id and accuracy_profile from SharedPreferences written at start time so recording
 * resumes automatically after a forced kill.
 *
 * ## Auto-pause
 * After 3h stationary (speed < 1 km/h), the service switches to one fix every 5 minutes
 * and marks subsequent points with isAutoPaused=true. On the first moving fix the normal
 * profile interval is restored and isAutoPaused reverts to false.
 *
 * ## Buffer
 * Points are buffered in an ArrayDeque and flushed in a single DB transaction every 10
 * points, or immediately on ACTION_STOP to prevent data loss.
 */
class LocationTrackingService : Service() {

    // --- Dependencies (created lazily; replaced by Koin in Task 9) ---
    private lateinit var db: AppDatabase
    private lateinit var trackPointRepo: TrackPointRepository
    private lateinit var locationProvider: LocationProvider
    private lateinit var prefs: SharedPreferences

    // --- Session state ---
    private var currentSessionId: String? = null
    private var currentProfile: AccuracyProfile = AccuracyProfile.STANDARD

    // --- Buffering ---
    private val buffer = ArrayDeque<TrackPointInsert>()

    // --- Coroutine scope for IO work (buffer flush). Cancelled in onDestroy. ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Auto-pause state ---
    private var isAutoPaused: Boolean = false

    // When true, no code path in this service will call locationProvider.startUpdates().
    // Set by stopGpsForTest() so injected synthetic fixes don't re-enable real GPS.
    @Volatile private var isGpsStoppedForTest = false
    // Set when speed first drops below threshold; cleared on movement.
    private var stationaryStartMs: Long? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        Log.i(TAG, "onCreate")

        db               = AppDatabase(DatabaseDriverFactory(this).createDriver())
        trackPointRepo   = TrackPointRepository(db)
        locationProvider = LocationProvider(this)
        prefs            = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Notification channel must exist before startForeground(). Will be moved to
        // Application.onCreate() in Task 9 to avoid repeated creation on service restart.
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val profileName = intent.getStringExtra(EXTRA_ACCURACY_PROFILE)
                    ?: AccuracyProfile.STANDARD.name
                startRecording(sessionId, profileName)
            }
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            null -> {
                // System restart: recover from SharedPreferences.
                val sessionId = prefs.getString(PREFS_SESSION_ID, null)
                val profileName = prefs.getString(PREFS_PROFILE, AccuracyProfile.STANDARD.name)
                    ?: AccuracyProfile.STANDARD.name
                if (sessionId == null) {
                    Log.w(TAG, "Null intent with no saved session — stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Recovering session $sessionId from SharedPreferences")
                startRecording(sessionId, profileName)
            }
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        runningInstance = null
        serviceScope.cancel()
        locationProvider.stopUpdates()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Recording control
    // -------------------------------------------------------------------------

    private fun startRecording(sessionId: String?, profileName: String) {
        if (sessionId == null) {
            Log.e(TAG, "Cannot start recording: sessionId is null")
            stopSelf()
            return
        }

        currentSessionId = sessionId
        currentProfile   = runCatching { AccuracyProfile.valueOf(profileName) }
            .getOrElse {
                Log.w(TAG, "Unknown profile '$profileName', falling back to STANDARD")
                AccuracyProfile.STANDARD
            }

        // Persist for START_STICKY recovery.
        prefs.edit()
            .putString(PREFS_SESSION_ID, sessionId)
            .putString(PREFS_PROFILE, currentProfile.name)
            .apply()

        Log.i(TAG, "Starting recording: session=$sessionId, profile=$currentProfile")

        startForeground(NOTIFICATION_ID, buildNotification())

        locationProvider.startUpdates(
            intervalMs  = currentProfile.movingIntervalMs,
            priority    = currentProfile.priority,
            onLocation  = ::onLocationReceived
        )
    }

    private fun stopRecording() {
        Log.i(TAG, "Stopping recording: session=$currentSessionId")

        locationProvider.stopUpdates()

        // Flush any remaining buffered points before stopping.
        flushBuffer()

        // Clear recovery prefs.
        prefs.edit()
            .remove(PREFS_SESSION_ID)
            .remove(PREFS_PROFILE)
            .apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Location callback
    // -------------------------------------------------------------------------

    /**
     * Called on the main looper for every GPS fix. Classifies transport mode,
     * manages auto-pause state, builds a [TrackPointInsert], and triggers a
     * buffer flush when the batch threshold is reached.
     */
    private fun onLocationReceived(data: LocationData) {
        val sessionId = currentSessionId ?: return

        val speedKmh = (data.speedMs ?: 0f) * 3.6f
        val mode     = TransportClassifier.classify(speedKmh.toDouble())

        // --- Auto-pause tracking ---
        if (speedKmh < STATIONARY_SPEED_KMH) {
            if (stationaryStartMs == null) {
                stationaryStartMs = data.timestampMs
                Log.d(TAG, "Device became stationary at ${data.timestampMs}")
            }
            val stationaryDurationMs = data.timestampMs - (stationaryStartMs ?: data.timestampMs)
            if (stationaryDurationMs >= AUTO_PAUSE_THRESHOLD_MS && !isAutoPaused) {
                enterAutoPause()
            }
        } else {
            stationaryStartMs = null
            if (isAutoPaused) {
                exitAutoPause()
            }
        }

        val point = TrackPointInsert(
            sessionId     = sessionId,
            timestamp     = data.timestampMs,
            latitude      = data.latitude,
            longitude     = data.longitude,
            altitude      = data.altitude,
            accuracy      = data.accuracyMeters,
            speed         = data.speedMs,
            transportMode = mode,
            isAutoPaused  = isAutoPaused
        )

        buffer.addLast(point)
        Log.d(TAG, "Buffered point #${buffer.size}: mode=$mode, autoPaused=$isAutoPaused")

        if (buffer.size >= BUFFER_FLUSH_SIZE) {
            flushBuffer()
        }

        // Adjust interval based on movement if not in auto-pause.
        // Skip when GPS is disabled for testing (isGpsStoppedForTest) to prevent
        // injected fixes from re-enabling the real FusedLocationProviderClient.
        if (!isAutoPaused && !isGpsStoppedForTest) {
            val desiredInterval = if (speedKmh >= STATIONARY_SPEED_KMH)
                currentProfile.movingIntervalMs
            else
                currentProfile.stationaryIntervalMs
            // Note: LocationProvider doesn't expose the active interval, so we re-apply
            // on every fix. The FusedClient de-duplicates identical requests internally.
            locationProvider.startUpdates(desiredInterval, currentProfile.priority, ::onLocationReceived)
        }
    }

    // -------------------------------------------------------------------------
    // Auto-pause transitions
    // -------------------------------------------------------------------------

    /**
     * Enters auto-pause mode: switches to 5-minute fix interval and marks
     * subsequent points with isAutoPaused=true.
     */
    private fun enterAutoPause() {
        isAutoPaused = true
        Log.i(TAG, "Entering auto-pause: switching to ${AUTO_PAUSE_INTERVAL_MS}ms interval")
        if (!isGpsStoppedForTest) {
            locationProvider.startUpdates(AUTO_PAUSE_INTERVAL_MS, currentProfile.priority, ::onLocationReceived)
        }
    }

    /**
     * Exits auto-pause mode: restores the profile's moving interval.
     */
    private fun exitAutoPause() {
        isAutoPaused = false
        stationaryStartMs = null
        Log.i(TAG, "Exiting auto-pause: restoring ${currentProfile.movingIntervalMs}ms interval")
        if (!isGpsStoppedForTest) {
            locationProvider.startUpdates(currentProfile.movingIntervalMs, currentProfile.priority, ::onLocationReceived)
        }
    }

    // -------------------------------------------------------------------------
    // Buffer flush
    // -------------------------------------------------------------------------

    /**
     * Flushes all buffered [TrackPointInsert] records to the database in a single
     * transaction. Called when the buffer hits [BUFFER_FLUSH_SIZE] or on service stop.
     */
    private fun flushBuffer() {
        if (buffer.isEmpty()) return
        val toFlush = buffer.toList()
        buffer.clear()
        Log.d(TAG, "Flushing ${toFlush.size} points to DB")
        serviceScope.launch {
            try {
                trackPointRepo.insertBatch(toFlush)
                Log.d(TAG, "Flushed ${toFlush.size} points successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush ${toFlush.size} points", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TripLens Recording",
            // IMPORTANCE_LOW: silent, no sound, no heads-up — respects background recording UX.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while TripLens is recording a trip"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("TripLens is recording")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        // No action buttons in MVP — stop is handled from the in-app UI.
        .setOngoing(true)
        .build()

    // -------------------------------------------------------------------------
    // Companion — Intent API
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Test hooks — package-private; only used by instrumented tests
    // -------------------------------------------------------------------------

    /** Feeds a [LocationData] fix directly into the location callback. Used by tests
     *  to simulate GPS fixes without a real FusedLocationProviderClient. */
    internal fun onLocationReceivedForTest(data: LocationData) = onLocationReceived(data)

    /** Exposes [isAutoPaused] state for assertion in instrumented tests. */
    internal fun isAutoPausedForTest(): Boolean = isAutoPaused

    /**
     * Stops real GPS delivery and prevents any code path from restarting it, so
     * synthetic [LocationData] can be injected without interference. Call this
     * immediately after [startService] in tests that inject fixes directly.
     */
    internal fun stopGpsForTest() {
        isGpsStoppedForTest = true
        locationProvider.stopUpdates()
    }

    /**
     * Resets stationary-tracking state and clears the point buffer to a clean baseline.
     * Call after [stopGpsForTest] to prevent real GPS fixes delivered between service
     * start and GPS stop from pre-seeding [stationaryStartMs] or polluting the buffer.
     */
    internal fun resetStationaryStateForTest() {
        stationaryStartMs = null
        isAutoPaused = false
        buffer.clear()
    }

    companion object {
        const val ACTION_START           = "com.cooldog.triplens.ACTION_START"
        const val ACTION_STOP            = "com.cooldog.triplens.ACTION_STOP"
        const val EXTRA_SESSION_ID       = "session_id"
        const val EXTRA_ACCURACY_PROFILE = "accuracy_profile"

        /**
         * Set to the running service instance in [onCreate]; cleared in [onDestroy].
         * Enables tests to reach internal state without reflection or a Binder.
         * Only valid while the service is running.
         */
        @Volatile
        internal var runningInstance: LocationTrackingService? = null
    }
}
