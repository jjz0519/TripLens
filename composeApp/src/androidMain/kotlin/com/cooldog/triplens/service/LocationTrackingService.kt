package com.cooldog.triplens.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cooldog.triplens.RECORDING_CHANNEL_ID
import com.cooldog.triplens.domain.TransportClassifier
import com.cooldog.triplens.model.MediaType
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.platform.AndroidGalleryScanner
import com.cooldog.triplens.platform.GalleryScanner
import com.cooldog.triplens.platform.LocationData
import com.cooldog.triplens.platform.LocationProvider
import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.TrackPointInsert
import com.cooldog.triplens.repository.TrackPointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "TripLens/LocationService"

private const val NOTIFICATION_ID         = 1001
private const val PREFS_NAME              = "triplens_service"
private const val PREFS_SESSION_ID        = "active_session_id"
private const val PREFS_PROFILE           = "accuracy_profile"
private const val PREFS_SESSION_START_TIME = "session_start_time"
// Flush every point immediately so the ViewModel's 3-second poll always finds recent data.
// A 10-point batch at 3s intervals would mean 30s before the first map update — the user
// could finish a 200m walk before seeing a single polyline segment.
private const val BUFFER_FLUSH_SIZE       = 1

// Auto-pause: enter after this many consecutive milliseconds below STATIONARY_SPEED_KMH.
private const val AUTO_PAUSE_THRESHOLD_MS = 3 * 60 * 60 * 1000L  // 3 hours
// Reduced polling interval during auto-pause (TDD §4.3).
private const val AUTO_PAUSE_INTERVAL_MS  = 5 * 60 * 1000L       // 5 minutes
// Speed below which the device is considered stationary.
private const val STATIONARY_SPEED_KMH   = 1.0f
// Gallery scan interval. TDD §5.1 says 60 s; we use 15 s so photos/videos appear quickly.
private const val GALLERY_SCAN_INTERVAL_MS = 15_000L

/**
 * ForegroundService that owns the GPS recording lifecycle.
 *
 * ## Intent API
 * Start recording:  ACTION_START with extras session_id (String), accuracy_profile (String),
 *                   session_start_time (Long, epoch ms)
 * Stop recording:   ACTION_STOP  (no extras needed)
 *
 * ## Recovery (START_STICKY)
 * On a system restart the OS delivers onStartCommand with a null intent. The service reads
 * session_id, accuracy_profile, and session_start_time from SharedPreferences written at start
 * time so recording resumes automatically after a forced kill.
 *
 * ## Auto-pause
 * After 3h stationary (speed < 1 km/h), the service switches to one fix every 5 minutes
 * and marks subsequent points with isAutoPaused=true. On the first moving fix the normal
 * profile interval is restored and isAutoPaused reverts to false.
 *
 * ## Buffer
 * Points are buffered in an ArrayDeque and flushed in a single DB transaction every 10
 * points, or immediately on ACTION_STOP to prevent data loss.
 *
 * ## Gallery scanning
 * Every [GALLERY_SCAN_INTERVAL_MS] (60 s) a coroutine queries MediaStore for new photos/videos
 * taken since [sessionStartTime] and inserts them into [MediaRefRepository]. Deduplication
 * by content_uri is enforced by [MediaRefRepository.insertIfNotExists].
 *
 * ## Live notification
 * The foreground notification shows distance, photo, video, text-note, and voice-note counts.
 * It is refreshed after every buffer flush and every gallery scan so counts stay current
 * without excessive DB queries.
 */
class LocationTrackingService : Service() {

    // --- Dependencies injected by Koin ---
    private val trackPointRepo: TrackPointRepository by inject()
    private val mediaRefRepo:   MediaRefRepository   by inject()
    private val noteRepo:       NoteRepository       by inject()
    private val locationProvider: LocationProvider   by inject()
    private val galleryScanner: GalleryScanner       by inject()

    private lateinit var prefs: SharedPreferences

    // --- Session state ---
    private var currentSessionId: String?     = null
    private var currentProfile: AccuracyProfile = AccuracyProfile.STANDARD
    private var sessionStartTime: Long          = 0L

    // --- Buffering ---
    private val buffer = ArrayDeque<TrackPointInsert>()

    // --- Coroutine scope for IO work (buffer flush, gallery scan). Cancelled in onDestroy. ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var galleryScanJob: Job? = null

    // --- Auto-pause state ---
    private var isAutoPaused: Boolean = false

    // When true, no code path in this service will call locationProvider.startUpdates().
    // Set by stopGpsForTest() so injected synthetic fixes don't re-enable real GPS.
    @Volatile private var isGpsStoppedForTest = false
    // Set when speed first drops below threshold; cleared on movement.
    private var stationaryStartMs: Long? = null

    // --- Distance tracking ---
    // Accumulated haversine distance across all non-auto-paused GPS fixes in this session.
    // Updated incrementally so we never need a full DB scan to compute distance for the
    // notification. Resets to 0 when a new session starts.
    private var cumulativeDistanceMeters: Double = 0.0
    private var prevLat: Double? = null
    private var prevLng: Double? = null

    // --- GPS interval tracking ---
    // Tracks the interval that was last passed to locationProvider.startUpdates() so we only
    // re-register when the desired interval actually changes. Re-registering on every fix
    // causes unnecessary FLP churn (cancel + recreate a LocationRequest on every callback).
    // Initialised to -1L so the first comparison in onLocationReceived always triggers a
    // registration (the initial startUpdates in startRecording uses movingIntervalMs directly).
    private var currentGpsIntervalMs: Long = -1L

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        Log.i(TAG, "onCreate")
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val sessionId  = intent.getStringExtra(EXTRA_SESSION_ID)
                val profileName = intent.getStringExtra(EXTRA_ACCURACY_PROFILE)
                    ?: AccuracyProfile.STANDARD.name
                val startTime  = intent.getLongExtra(EXTRA_SESSION_START_TIME, System.currentTimeMillis())
                startRecording(sessionId, profileName, startTime)
            }
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            null -> {
                // System restart: recover from SharedPreferences.
                val sessionId   = prefs.getString(PREFS_SESSION_ID, null)
                val profileName = prefs.getString(PREFS_PROFILE, AccuracyProfile.STANDARD.name)
                    ?: AccuracyProfile.STANDARD.name
                val startTime   = prefs.getLong(PREFS_SESSION_START_TIME, System.currentTimeMillis())
                if (sessionId == null) {
                    Log.w(TAG, "Null intent with no saved session — stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Recovering session $sessionId from SharedPreferences")
                startRecording(sessionId, profileName, startTime)
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

    private fun startRecording(sessionId: String?, profileName: String, startTime: Long) {
        if (sessionId == null) {
            Log.e(TAG, "Cannot start recording: sessionId is null")
            stopSelf()
            return
        }

        currentSessionId = sessionId
        sessionStartTime = startTime
        currentProfile   = runCatching { AccuracyProfile.valueOf(profileName) }
            .getOrElse {
                Log.w(TAG, "Unknown profile '$profileName', falling back to STANDARD")
                AccuracyProfile.STANDARD
            }

        // Reset per-session distance and GPS interval tracking.
        cumulativeDistanceMeters = 0.0
        prevLat = null
        prevLng = null
        currentGpsIntervalMs = currentProfile.movingIntervalMs

        // Persist for START_STICKY recovery.
        prefs.edit()
            .putString(PREFS_SESSION_ID, sessionId)
            .putString(PREFS_PROFILE, currentProfile.name)
            .putLong(PREFS_SESSION_START_TIME, sessionStartTime)
            .apply()

        Log.i(TAG, "Starting recording: session=$sessionId, profile=$currentProfile, " +
                "startTime=$sessionStartTime")

        startForeground(NOTIFICATION_ID, buildNotification())

        locationProvider.startUpdates(
            intervalMs = currentProfile.movingIntervalMs,
            priority   = currentProfile.priority,
            onLocation = ::onLocationReceived
        )

        startGalleryScanLoop()
    }

    private fun stopRecording() {
        Log.i(TAG, "Stopping recording: session=$currentSessionId")

        galleryScanJob?.cancel()
        galleryScanJob = null

        locationProvider.stopUpdates()
        flushBuffer()

        prefs.edit()
            .remove(PREFS_SESSION_ID)
            .remove(PREFS_PROFILE)
            .remove(PREFS_SESSION_START_TIME)
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

        // When speedMs is null (common for network/cell-fused fixes or the first fix from FLP),
        // assume the device is moving rather than defaulting to 0 km/h. Defaulting to 0 would
        // immediately switch the GPS interval to the 60-second stationary interval, producing a
        // sparse track for short walks. STATIONARY_SPEED_KMH + 1f (2.0 km/h) is safely in the
        // WALKING range, so null-speed fixes are classified and tracked as walking.
        val speedKmh = data.speedMs?.let { it * 3.6f } ?: (STATIONARY_SPEED_KMH + 1f)
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

        // --- Incremental distance ---
        // Only accumulate distance for non-paused fixes so auto-pause gaps don't inflate stats.
        if (!isAutoPaused) {
            val prev = prevLat
            val prevL = prevLng
            if (prev != null && prevL != null) {
                cumulativeDistanceMeters += haversineMeters(prev, prevL, data.latitude, data.longitude)
            }
            prevLat = data.latitude
            prevLng = data.longitude
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

        if (!isAutoPaused && !isGpsStoppedForTest) {
            val desiredInterval = if (speedKmh >= STATIONARY_SPEED_KMH)
                currentProfile.movingIntervalMs
            else
                currentProfile.stationaryIntervalMs
            // Only re-register with FLP when the desired interval changes (moving ↔ stationary
            // transition). Re-registering on every fix would cancel and recreate the LocationRequest
            // on every callback, which resets the FLP's internal delivery timer unnecessarily.
            if (desiredInterval != currentGpsIntervalMs) {
                currentGpsIntervalMs = desiredInterval
                locationProvider.startUpdates(desiredInterval, currentProfile.priority, ::onLocationReceived)
                Log.d(TAG, "GPS interval changed to ${desiredInterval}ms (speedKmh=$speedKmh)")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auto-pause transitions
    // -------------------------------------------------------------------------

    private fun enterAutoPause() {
        isAutoPaused = true
        currentGpsIntervalMs = AUTO_PAUSE_INTERVAL_MS
        Log.i(TAG, "Entering auto-pause: switching to ${AUTO_PAUSE_INTERVAL_MS}ms interval")
        if (!isGpsStoppedForTest) {
            locationProvider.startUpdates(AUTO_PAUSE_INTERVAL_MS, currentProfile.priority, ::onLocationReceived)
        }
    }

    private fun exitAutoPause() {
        isAutoPaused = false
        stationaryStartMs = null
        currentGpsIntervalMs = currentProfile.movingIntervalMs
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
     * transaction, then refreshes the foreground notification with latest stats.
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
            // Refresh notification after flush regardless of flush success — distance
            // is tracked in-memory so it is always accurate even if the DB write failed.
            updateNotification()
        }
    }

    // -------------------------------------------------------------------------
    // Gallery scanning
    // -------------------------------------------------------------------------

    /**
     * Launches a repeating coroutine that scans MediaStore for new photos/videos
     * every [GALLERY_SCAN_INTERVAL_MS] and inserts them into [MediaRefRepository].
     *
     * The first scan is intentionally delayed by one interval so it runs after the
     * user has had a chance to take a photo; an immediate scan on session start would
     * almost always return zero results.
     *
     * Deduplication is enforced by [MediaRefRepository.insertIfNotExists] so restarted
     * sessions (START_STICKY recovery) cannot create duplicate rows.
     */
    private fun startGalleryScanLoop() {
        galleryScanJob = serviceScope.launch {
            while (isActive) {
                delay(GALLERY_SCAN_INTERVAL_MS)
                runGalleryScan()
            }
        }
        Log.i(TAG, "Gallery scan loop started (interval=${GALLERY_SCAN_INTERVAL_MS}ms)")
    }

    private suspend fun runGalleryScan() {
        val sessionId = currentSessionId ?: return
        try {
            val scanned = galleryScanner.scanNewMedia(sessionStartTime)
            if (scanned.isEmpty()) {
                Log.d(TAG, "Gallery scan: no new media")
                return
            }
            for (media in scanned) {
                mediaRefRepo.insertIfNotExists(
                    id         = UUID.randomUUID().toString(),
                    sessionId  = sessionId,
                    type       = media.mediaType.name.lowercase(),
                    source     = "phone_gallery",
                    contentUri = media.contentUri,
                    filename   = media.filename,
                    capturedAt = media.capturedAt,
                )
            }
            Log.i(TAG, "Gallery scan: inserted ${scanned.size} new items " +
                    "(${scanned.count { it.mediaType == MediaType.PHOTO }} photos, " +
                    "${scanned.count { it.mediaType == MediaType.VIDEO }} videos)")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Gallery scan failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    /**
     * Queries the DB for current media/note counts and posts an updated notification.
     * Called after every buffer flush and gallery scan — at most once per ~60 s during
     * normal movement, so the DB query cost is negligible.
     *
     * Must be called from within [serviceScope] (already on IO thread).
     */
    private suspend fun updateNotification() {
        val sessionId = currentSessionId ?: return
        try {
            val mediaRefs   = mediaRefRepo.getBySession(sessionId)
            val photoCount  = mediaRefs.count { it.type == MediaType.PHOTO }
            val videoCount  = mediaRefs.count { it.type == MediaType.VIDEO }
            val notes       = noteRepo.getBySession(sessionId)
            val textCount   = notes.count { it.type == NoteType.TEXT }
            val voiceCount  = notes.count { it.type == NoteType.VOICE }

            val notification = buildNotification(
                distanceMeters = cumulativeDistanceMeters,
                photoCount     = photoCount,
                videoCount     = videoCount,
                textCount      = textCount,
                voiceCount     = voiceCount,
            )
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification stats", e)
        }
    }

    /**
     * Builds the foreground notification.
     *
     * Content line format (non-zero counts only):
     *   "1.2 km  •  3 photos  •  2 notes  •  1 voice"
     * When nothing has been recorded yet the content text is "Starting…"
     */
    private fun buildNotification(
        distanceMeters: Double = 0.0,
        photoCount:     Int    = 0,
        videoCount:     Int    = 0,
        textCount:      Int    = 0,
        voiceCount:     Int    = 0,
    ) = NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
        .setContentTitle("TripLens is recording")
        .setContentText(buildStatsLine(distanceMeters, photoCount, videoCount, textCount, voiceCount))
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        // Allow the OS to show the full text on lock screen / expanded shade.
        .setStyle(NotificationCompat.BigTextStyle()
            .bigText(buildStatsLine(distanceMeters, photoCount, videoCount, textCount, voiceCount)))
        .build()

    /**
     * Formats the notification stats line.
     *
     * Distance is shown as metres below 1 km ("800 m") and kilometres above ("1.2 km").
     * Counts use "1 photo" / "2 photos" pluralisation for clarity.
     * Returns "Starting…" until the first GPS fix arrives (distanceMeters == 0 and all counts == 0).
     */
    private fun buildStatsLine(
        distanceMeters: Double,
        photoCount: Int,
        videoCount: Int,
        textCount:  Int,
        voiceCount: Int,
    ): String {
        if (distanceMeters == 0.0 && photoCount == 0 && videoCount == 0
            && textCount == 0 && voiceCount == 0) {
            return "Starting…"
        }
        return buildString {
            val dist = if (distanceMeters < 1000) {
                "${distanceMeters.toInt()} m"
            } else {
                "${"%.1f".format(distanceMeters / 1000)} km"
            }
            append(dist)
            if (photoCount > 0) append("  •  $photoCount ${if (photoCount == 1) "photo" else "photos"}")
            if (videoCount > 0) append("  •  $videoCount ${if (videoCount == 1) "video" else "videos"}")
            if (textCount  > 0) append("  •  $textCount ${if (textCount  == 1) "note"  else "notes"}")
            if (voiceCount > 0) append("  •  $voiceCount voice")
        }
    }

    // -------------------------------------------------------------------------
    // Distance helper
    // -------------------------------------------------------------------------

    /**
     * Haversine distance in metres between two WGS-84 coordinates.
     * Duplicated from [com.cooldog.triplens.domain.SegmentSmoother] (private there) to
     * avoid a cross-module dependency for a 6-line math function.
     */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r  = 6_371_000.0
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δφ = Math.toRadians(lat2 - lat1)
        val Δλ = Math.toRadians(lng2 - lng1)
        val a  = sin(Δφ / 2).pow(2) + cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // -------------------------------------------------------------------------
    // Test hooks — package-private; only used by instrumented tests
    // -------------------------------------------------------------------------

    internal fun onLocationReceivedForTest(data: LocationData) = onLocationReceived(data)
    internal fun isAutoPausedForTest(): Boolean = isAutoPaused

    internal fun stopGpsForTest() {
        isGpsStoppedForTest = true
        locationProvider.stopUpdates()
    }

    internal fun resetStationaryStateForTest() {
        stationaryStartMs = null
        isAutoPaused = false
        buffer.clear()
    }

    // -------------------------------------------------------------------------
    // Companion — Intent API
    // -------------------------------------------------------------------------

    companion object {
        const val ACTION_START                = "com.cooldog.triplens.ACTION_START"
        const val ACTION_STOP                 = "com.cooldog.triplens.ACTION_STOP"
        const val EXTRA_SESSION_ID            = "session_id"
        const val EXTRA_ACCURACY_PROFILE      = "accuracy_profile"
        const val EXTRA_SESSION_START_TIME    = "session_start_time"

        /**
         * Set to the running service instance in [onCreate]; cleared in [onDestroy].
         * Enables tests to reach internal state without reflection or a Binder.
         */
        @Volatile
        internal var runningInstance: LocationTrackingService? = null
    }
}
