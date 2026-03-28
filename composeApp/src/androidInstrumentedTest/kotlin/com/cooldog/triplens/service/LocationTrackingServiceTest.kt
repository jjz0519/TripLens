package com.cooldog.triplens.service

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.cooldog.triplens.MainActivity
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.db.DatabaseDriverFactory
import com.cooldog.triplens.repository.TrackPointRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumented tests for [LocationTrackingService].
 *
 * ## Why no ServiceTestRule
 * [LocationTrackingService] is a started (not bound) service — [onBind] returns null.
 * [ServiceTestRule.startService] internally binds to get a connection callback, which
 * never fires for null-binding services. Instead, we use direct [Context.startService]
 * calls and poll [LocationTrackingService.runningInstance] to synchronise tests.
 *
 * ## Android 14+ requirements for location FGS
 * 1. ACCESS_FINE_LOCATION granted at runtime → [permissionRule]
 * 2. App in the foreground → [activityRule] launches MainActivity before each test
 */
@RunWith(AndroidJUnit4::class)
class LocationTrackingServiceTest {

    // Rule order: permissions granted first, then activity launched (foreground), then tests run.
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var trackPointRepo: TrackPointRepository

    // Unique per run so leftover DB rows from a previous test execution don't interfere.
    private val testSessionId = "test-session-${System.currentTimeMillis()}"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase(DatabaseDriverFactory(context).createDriver())
        trackPointRepo = TrackPointRepository(db)
    }

    @After
    fun tearDown() {
        // Stop the service if still running (covers test failure paths).
        sendStop()
        waitForServiceStop(timeoutMs = 3_000)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Sends ACTION_START and waits up to [timeoutMs] for the service instance to appear. */
    private fun startService(
        sessionId: String = testSessionId,
        profile: String = "STANDARD",
        timeoutMs: Long = 5_000
    ) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_SESSION_ID, sessionId)
            putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, profile)
        }
        context.startService(intent)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (LocationTrackingService.runningInstance == null &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertNotNull(
            "LocationTrackingService did not start within ${timeoutMs}ms",
            LocationTrackingService.runningInstance
        )
    }

    private fun sendStop() {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        context.startService(intent)
    }

    private fun waitForServiceStop(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (LocationTrackingService.runningInstance != null &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    // -------------------------------------------------------------------------
    // Test 5: Service lifecycle — start, notification, stop
    // -------------------------------------------------------------------------

    /**
     * Starting the service with a valid session_id causes it to become running
     * (runningInstance non-null). Sending ACTION_STOP causes it to stop
     * (runningInstance null).
     *
     * Notification correctness is verified implicitly: a location FGS that fails
     * to call startForeground() within 5 seconds is ANR-killed by Android, which
     * would cause the runningInstance wait to time out.
     */
    @Test
    fun serviceLifecycle_startAndStop() {
        startService()
        assertNotNull("Service should be running", LocationTrackingService.runningInstance)

        sendStop()
        waitForServiceStop()

        assertNull("Service should be stopped", LocationTrackingService.runningInstance)
    }

    // -------------------------------------------------------------------------
    // Test 6: START_STICKY recovery via SharedPreferences
    // -------------------------------------------------------------------------

    @Test
    fun recovery_nullIntentReadsFromSharedPreferences() {
        val prefs = context.getSharedPreferences("triplens_service", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Normal start — session_id should be persisted.
        startService()
        val savedSessionId = prefs.getString("active_session_id", null)
        assertEquals("Session ID should be saved on start", testSessionId, savedSessionId)

        // Normal stop — session_id should be cleared.
        sendStop()
        waitForServiceStop()

        val clearedSessionId = prefs.getString("active_session_id", null)
        assertNull("Session ID should be cleared on stop", clearedSessionId)
    }

    // -------------------------------------------------------------------------
    // Test 7: Auto-pause trigger after 3h stationary
    // -------------------------------------------------------------------------

    @Test
    fun autoPause_triggersAfterThreeHoursStationary() = runTest {
        startService()
        // Stop real GPS, then reset stationary state to clear any pre-seeded stationaryStartMs
        // from a real fix delivered between service start and GPS stop.
        LocationTrackingServiceTestHelper.stopGps()
        LocationTrackingServiceTestHelper.resetStationaryState()

        val baseTimeMs = System.currentTimeMillis() - (3 * 60 * 60 * 1000L + 1_000L)

        // Two stationary fixes spanning just over 3 hours.
        LocationTrackingServiceTestHelper.injectLocation(
            com.cooldog.triplens.platform.LocationData(
                timestampMs    = baseTimeMs,
                latitude       = 22.3193,
                longitude      = 114.1694,
                altitude       = 10.0,
                accuracyMeters = 5f,
                speedMs        = 0f
            )
        )
        LocationTrackingServiceTestHelper.injectLocation(
            com.cooldog.triplens.platform.LocationData(
                timestampMs    = System.currentTimeMillis(),
                latitude       = 22.3193,
                longitude      = 114.1694,
                altitude       = 10.0,
                accuracyMeters = 5f,
                speedMs        = 0f
            )
        )
        Thread.sleep(100)

        assertTrue(
            "Service should enter auto-pause after 3h+ stationary",
            LocationTrackingServiceTestHelper.isAutoPaused()
        )
    }

    // -------------------------------------------------------------------------
    // Test 8: Auto-pause resume on movement
    // -------------------------------------------------------------------------

    @Test
    fun autoPause_resumesOnMovement() = runTest {
        startService()
        LocationTrackingServiceTestHelper.stopGps()
        LocationTrackingServiceTestHelper.resetStationaryState()

        // Trigger auto-pause.
        val baseTimeMs = System.currentTimeMillis() - (3 * 60 * 60 * 1000L + 1_000L)
        LocationTrackingServiceTestHelper.injectLocation(
            com.cooldog.triplens.platform.LocationData(
                baseTimeMs, 22.3193, 114.1694, null, 5f, 0f
            )
        )
        LocationTrackingServiceTestHelper.injectLocation(
            com.cooldog.triplens.platform.LocationData(
                System.currentTimeMillis(), 22.3193, 114.1694, null, 5f, 0f
            )
        )
        Thread.sleep(100)
        assertTrue("Pre-condition: should be in auto-pause",
            LocationTrackingServiceTestHelper.isAutoPaused())

        // Inject a moving fix (2.78 m/s ≈ 10 km/h).
        LocationTrackingServiceTestHelper.injectLocation(
            com.cooldog.triplens.platform.LocationData(
                System.currentTimeMillis() + 1_000L,
                22.3200, 114.1700, null, 5f, speedMs = 2.78f
            )
        )
        Thread.sleep(100)

        assertFalse(
            "Service should exit auto-pause after a moving fix",
            LocationTrackingServiceTestHelper.isAutoPaused()
        )
    }

    // -------------------------------------------------------------------------
    // Test 9: Buffer flush on stop with fewer than 10 points
    // -------------------------------------------------------------------------

    @Test
    fun bufferFlush_persistsAllPointsOnStop() = runTest {
        val now = System.currentTimeMillis()
        // trip_group must be inserted before session (FK constraint).
        db.tripGroupQueries.insert("test-group", "Test Group", now, now)
        db.sessionQueries.insert(testSessionId, "test-group", "Test Session", now, null, "recording")

        startService()
        LocationTrackingServiceTestHelper.stopGps()
        LocationTrackingServiceTestHelper.resetStationaryState()

        // Inject 5 fixes — below BUFFER_FLUSH_SIZE (10), so they stay buffered until stop.
        repeat(5) { i ->
            LocationTrackingServiceTestHelper.injectLocation(
                com.cooldog.triplens.platform.LocationData(
                    timestampMs    = now + i * 8_000L,
                    latitude       = 22.3193 + i * 0.0001,
                    longitude      = 114.1694 + i * 0.0001,
                    altitude       = 10.0,
                    accuracyMeters = 5f,
                    speedMs        = 2.0f
                )
            )
        }

        // Stop triggers flushBuffer() before stopSelf().
        sendStop()
        waitForServiceStop()
        Thread.sleep(500) // Allow the IO coroutine to finish writing.

        val persisted = trackPointRepo.getBySession(testSessionId)
        assertEquals("All 5 buffered points should be persisted on stop", 5, persisted.size)
    }
}
