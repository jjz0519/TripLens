package com.cooldog.triplens.platform

/**
 * Platform-agnostic location fix. Produced by [LocationProvider] and consumed by
 * [LocationTrackingService] to build [TrackPointInsert] records.
 *
 * All fields mirror what Android's FusedLocationProviderClient exposes; the iOS
 * CoreLocation actual will map CLLocation to the same shape.
 *
 * @param speedMs null when the platform cannot provide speed (e.g., first fix, no motion).
 * @param altitude null when the platform has no barometric/GPS altitude fix.
 */
data class LocationData(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,
    val speedMs: Float?
)
