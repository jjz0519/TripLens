package com.cooldog.triplens.ui.common

import com.cooldog.triplens.model.TransportMode

/**
 * Shared formatting utilities for distance, duration, and transport mode display.
 *
 * All three functions were previously duplicated across TripListScreen, TripDetailScreen,
 * and SessionReviewScreen. Extracted here so changes propagate consistently.
 */

/**
 * Formats a distance in metres for display.
 * - Under 1 km: "950 m"
 * - 1 km and above: "1.4 km"
 */
internal fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()} m"
    } else {
        "${"%.1f".format(meters / 1000)} km"
    }
}

/**
 * Formats a duration in seconds for display.
 * - Under 1 hour: "23m"
 * - 1 hour and above: "2h 15m"
 */
internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        else  -> "${m}m"
    }
}

/** Maps a [TransportMode] to its display emoji. */
internal fun modeEmoji(mode: TransportMode): String = when (mode) {
    TransportMode.STATIONARY   -> "⏸️"
    TransportMode.WALKING      -> "🚶"
    TransportMode.CYCLING      -> "🚲"
    TransportMode.DRIVING      -> "🚗"
    TransportMode.FAST_TRANSIT -> "🚄"
}
