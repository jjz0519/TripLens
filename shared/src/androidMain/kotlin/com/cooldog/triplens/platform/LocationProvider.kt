package com.cooldog.triplens.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

private const val TAG = "TripLens/LocationProvider"

/**
 * Android actual for [LocationProvider]. Wraps [FusedLocationProviderClient] to deliver
 * platform-agnostic [LocationData] objects to the caller.
 *
 * Constructor takes a [Context] (same pattern as DatabaseDriverFactory) so the
 * actual can initialise the Fused client. This is an Android-only detail; the
 * expect class has no constructor, keeping commonMain code platform-agnostic.
 *
 * Calling [startUpdates] while updates are already running replaces the previous
 * request — this is used by [LocationTrackingService] to switch intervals during
 * auto-pause transitions without creating a second callback.
 */
actual class LocationProvider(context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    // Retained so stopUpdates() can remove the exact callback registered by startUpdates().
    private var activeCallback: LocationCallback? = null

    /**
     * Starts (or restarts) location updates.
     *
     * @param intervalMs Desired delivery interval in milliseconds.
     * @param priority   Maps to GMS [Priority] constants — see [toGmsPriority].
     * @param onLocation Called on the main looper for every new [LocationData] fix.
     */
    @SuppressLint("MissingPermission") // Permission is checked before the service starts.
    actual fun startUpdates(
        intervalMs: Long,
        priority: LocationPriority,
        onLocation: (LocationData) -> Unit
    ) {
        // Remove any existing subscription before creating a new one to avoid double delivery.
        activeCallback?.let { fusedClient.removeLocationUpdates(it) }

        val request = LocationRequest.Builder(priority.toGmsPriority(), intervalMs).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                Log.d(TAG, "Fix received: lat=${location.latitude}, lng=${location.longitude}, " +
                        "acc=${location.accuracy}m, spd=${location.speed}m/s")
                onLocation(
                    LocationData(
                        timestampMs = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        // altitude is always set on Android, but hasAltitude() guards against stale 0.0
                        altitude = if (location.hasAltitude()) location.altitude else null,
                        accuracyMeters = location.accuracy,
                        speedMs = if (location.hasSpeed()) location.speed else null
                    )
                )
            }
        }

        activeCallback = callback

        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.i(TAG, "Location updates started: intervalMs=$intervalMs, priority=$priority")
        } catch (e: SecurityException) {
            // Should not happen: the service checks permissions before starting.
            Log.e(TAG, "Location permission not granted when requesting updates", e)
        }
    }

    /** Stops location delivery and clears the active callback. */
    actual fun stopUpdates() {
        activeCallback?.let {
            fusedClient.removeLocationUpdates(it)
            activeCallback = null
            Log.i(TAG, "Location updates stopped")
        }
    }

    // Maps our platform-agnostic priority to the GMS Priority int constant.
    private fun LocationPriority.toGmsPriority(): Int = when (this) {
        LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
        LocationPriority.BALANCED      -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationPriority.LOW_POWER     -> Priority.PRIORITY_LOW_POWER
    }
}
