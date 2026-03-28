package com.cooldog.triplens.service

import com.cooldog.triplens.platform.LocationData

/**
 * Test-only helper for injecting synthetic [LocationData] into the running
 * [LocationTrackingService] instance and reading its internal state.
 *
 * Uses the [LocationTrackingService.runningInstance] companion reference set
 * in [LocationTrackingService.onCreate]. Callers must ensure the service is
 * running before invoking any method here.
 */
internal object LocationTrackingServiceTestHelper {

    private fun requireService(): LocationTrackingService =
        checkNotNull(LocationTrackingService.runningInstance) {
            "LocationTrackingService is not running. Start it via serviceRule.startService() first."
        }

    /** Feeds [data] directly into the service's location callback, bypassing GPS hardware. */
    fun injectLocation(data: LocationData) {
        requireService().onLocationReceivedForTest(data)
    }

    /** Returns the current auto-pause state of the running service. */
    fun isAutoPaused(): Boolean = requireService().isAutoPausedForTest()

    /**
     * Stops real GPS delivery so that injected [LocationData] fixes are the only
     * input to the service during a test. Call this after the service has started
     * but before injecting test data.
     */
    fun stopGps() = requireService().stopGpsForTest()

    /**
     * Resets stationary-tracking state ([stationaryStartMs] and [isAutoPaused]).
     * Call after [stopGps] to ensure a real GPS fix received between service start
     * and GPS stop doesn't pre-seed the stationary timer.
     */
    fun resetStationaryState() = requireService().resetStationaryStateForTest()
}
