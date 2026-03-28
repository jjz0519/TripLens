package com.cooldog.triplens.domain

import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import kotlin.math.*

/**
 * Smooths raw per-point transport mode classifications into display-ready segments.
 *
 * Algorithm (TDD Section 6.2):
 * 1. Group consecutive points with the same mode into raw segments.
 * 2. For each segment with fewer than [minSegmentPoints] points:
 *    - If the segment before AND after it share the same mode, absorb the segment
 *      into that surrounding mode (GPS noise elimination).
 *    - If the noise segment is at the start or end (no neighbor on one side), keep it.
 * 3. After absorption, re-group into final Segment objects with computed stats.
 *
 * Smoothing runs at display time only. Raw per-point modes are preserved in the DB.
 *
 * @param points Ordered list of TrackPoints for a session (ascending timestamp).
 * @param minSegmentPoints Segments with fewer points than this are candidates for absorption.
 *                         For STANDARD accuracy profile (8s interval), use 3 (~24 seconds).
 */
object SegmentSmoother {

    fun smooth(points: List<TrackPoint>, minSegmentPoints: Int): List<Segment> {
        if (points.isEmpty()) return emptyList()

        // Step 1: group into raw (mode, list-of-points) pairs
        val rawGroups = mutableListOf<Pair<TransportMode, MutableList<TrackPoint>>>()
        for (point in points) {
            if (rawGroups.isEmpty() || rawGroups.last().first != point.transportMode) {
                rawGroups.add(point.transportMode to mutableListOf(point))
            } else {
                rawGroups.last().second.add(point)
            }
        }

        // Step 2: absorb noise segments flanked on both sides by the same mode
        val smoothed = rawGroups.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val result = mutableListOf<Pair<TransportMode, MutableList<TrackPoint>>>()
            var i = 0
            while (i < smoothed.size) {
                val (mode, pts) = smoothed[i]
                val prevMode = if (i > 0) smoothed[i - 1].first else null
                val nextMode = if (i < smoothed.size - 1) smoothed[i + 1].first else null

                if (pts.size < minSegmentPoints && prevMode != null && nextMode != null && prevMode == nextMode) {
                    // Absorb into surrounding mode by merging with the previous group
                    result.last().second.addAll(pts)
                    changed = true
                } else {
                    result.add(mode to pts.toMutableList())
                }
                i++
            }
            smoothed.clear()
            smoothed.addAll(result)
        }

        // Step 3: build Segment objects
        return smoothed.map { (mode, pts) ->
            val first = pts.first()
            val last = pts.last()
            val distance = computeDistance(pts)
            Segment(
                mode = mode,
                startTimestamp = first.timestamp,
                endTimestamp = last.timestamp,
                startLat = first.latitude,
                startLng = first.longitude,
                endLat = last.latitude,
                endLng = last.longitude,
                pointCount = pts.size,
                distanceMeters = distance,
                durationSeconds = (last.timestamp - first.timestamp) / 1_000L
            )
        }
    }

    /**
     * Haversine distance sum over consecutive point pairs, in metres.
     * Earth radius = 6,371,000 m (spherical approximation; sufficient for display).
     */
    private fun computeDistance(points: List<TrackPoint>): Double {
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

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
