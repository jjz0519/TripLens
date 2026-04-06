package com.cooldog.triplens.platform

/**
 * Named GPS accuracy profiles, each with interval and priority settings from TDD §4.2.
 *
 * Interval semantics:
 * - [movingIntervalMs]: used when the device is moving (speed >= 1 km/h)
 * - [stationaryIntervalMs]: used when the device is stationary (speed < 1 km/h but not auto-paused)
 *
 * During auto-pause (>3h stationary), [LocationTrackingService] overrides the interval
 * to 5 minutes (300_000ms) regardless of profile. The profile is restored on movement.
 */
enum class AccuracyProfile(
    val movingIntervalMs: Long,
    val stationaryIntervalMs: Long,
    val priority: LocationPriority
) {
    // Default: 3-second moving interval matches the Google Fit "walking" cadence and keeps
    // the track dense enough that short walks (200–500m) produce smooth polylines. 60-second
    // stationary interval still saves battery when the user stops.
    STANDARD(
        movingIntervalMs = 3_000,
        stationaryIntervalMs = 60_000,
        priority = LocationPriority.HIGH_ACCURACY
    ),

    // Maximum frequency: used when the user explicitly wants the densest track possible.
    HIGH(
        movingIntervalMs = 1_000,
        stationaryIntervalMs = 1_000,
        priority = LocationPriority.HIGH_ACCURACY
    ),

    // Relaxed accuracy and frequency: maximises battery life at the cost of track density.
    BATTERY_SAVER(
        movingIntervalMs = 45_000,
        stationaryIntervalMs = 60_000,
        priority = LocationPriority.BALANCED
    )
}
