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
    // Default: 8-second moving interval (TDD §4.2) — dense enough for smooth polylines on
    // short walks (200–500m) while remaining battery-efficient. 60-second stationary interval
    // saves battery whenever the user stops.
    STANDARD(
        movingIntervalMs = 8_000,
        stationaryIntervalMs = 60_000,
        priority = LocationPriority.HIGH_ACCURACY
    ),

    // Maximum frequency: used when the user explicitly wants the densest track possible
    // (e.g. hiking). 4-second interval applied both moving and stationary (TDD §4.2).
    HIGH(
        movingIntervalMs = 4_000,
        stationaryIntervalMs = 4_000,
        priority = LocationPriority.HIGH_ACCURACY
    ),

    // Relaxed accuracy and frequency: maximises battery life at the cost of track density.
    BATTERY_SAVER(
        movingIntervalMs = 45_000,
        stationaryIntervalMs = 60_000,
        priority = LocationPriority.BALANCED
    )
}
