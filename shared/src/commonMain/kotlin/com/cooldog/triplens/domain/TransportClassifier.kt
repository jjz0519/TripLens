package com.cooldog.triplens.domain

import com.cooldog.triplens.model.TransportMode

/**
 * Classifies a GPS speed reading into a transport mode.
 *
 * Algorithm: simple threshold comparison on km/h. Thresholds from TDD Section 6.1.
 * - < 1.0  → STATIONARY  (person standing still, waiting)
 * - 1–6    → WALKING      (brisk walk up to ~5.9 km/h)
 * - 6–20   → CYCLING      (slow bike to ~19.9 km/h)
 * - 20–120 → DRIVING      (car, bus, slow train)
 * - ≥ 120  → FAST_TRANSIT (high-speed rail, plane)
 *
 * Negative speed (GPS artifact) is treated as stationary.
 */
object TransportClassifier {

    private const val WALKING_MIN      = 1.0
    private const val CYCLING_MIN      = 6.0
    private const val DRIVING_MIN      = 20.0
    private const val FAST_TRANSIT_MIN = 120.0

    fun classify(speedKmh: Double): TransportMode = when {
        speedKmh < WALKING_MIN      -> TransportMode.STATIONARY
        speedKmh < CYCLING_MIN      -> TransportMode.WALKING
        speedKmh < DRIVING_MIN      -> TransportMode.CYCLING
        speedKmh < FAST_TRANSIT_MIN -> TransportMode.DRIVING
        else                        -> TransportMode.FAST_TRANSIT
    }
}
