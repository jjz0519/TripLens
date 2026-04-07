package com.cooldog.triplens.domain

import com.cooldog.triplens.model.TrackPoint
import kotlin.math.*

/**
 * Haversine distance utilities shared by [SegmentSmoother] (per-segment distance)
 * and [com.cooldog.triplens.ui.recording.RecordingViewModel] (incremental session distance).
 *
 * Earth radius = 6,371,000 m (spherical approximation; sufficient for display purposes).
 */
object HaversineUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Computes the haversine (great-circle) distance between two points in metres.
     */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Computes the total haversine distance across consecutive [TrackPoint] pairs, in metres.
     * Returns 0.0 for lists with fewer than 2 points.
     */
    fun totalDistance(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        return total
    }
}
