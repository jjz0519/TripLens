package com.cooldog.triplens.platform

/**
 * expect/actual abstraction for platform GPS hardware.
 *
 * The Android actual wraps FusedLocationProviderClient; the iOS actual (future)
 * will wrap CLLocationManager. No constructor is declared here — the actual class
 * takes a platform Context (Android) so it follows the same pattern as DatabaseDriverFactory.
 *
 * Usage:
 *   val provider = LocationProvider(context)          // Android only
 *   provider.startUpdates(8_000, HIGH_ACCURACY) { loc -> ... }
 *   provider.stopUpdates()
 */
expect class LocationProvider {
    fun startUpdates(intervalMs: Long, priority: LocationPriority, onLocation: (LocationData) -> Unit)
    fun stopUpdates()
}
