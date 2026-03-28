package com.cooldog.triplens.domain

import com.cooldog.triplens.model.TransportMode

/**
 * A contiguous run of TrackPoints sharing the same transport mode, after noise smoothing.
 * Used for map rendering (colored polylines) and the session timeline.
 */
data class Segment(
    val mode: TransportMode,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val pointCount: Int,
    val distanceMeters: Double,  // haversine sum of consecutive point pairs
    val durationSeconds: Long
)
