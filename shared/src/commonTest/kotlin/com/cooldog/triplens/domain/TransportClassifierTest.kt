package com.cooldog.triplens.domain

import com.cooldog.triplens.model.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TransportClassifierTest {

    // Speed thresholds from TDD Section 6.1:
    // stationary: < 1.0 km/h
    // walking:    1.0–5.9
    // cycling:    6.0–19.9
    // driving:    20.0–119.9
    // fast_transit: >= 120.0

    @Test fun below1_isStationary()     = check(0.0,   TransportMode.STATIONARY)
    @Test fun exactly0_isStationary()   = check(0.0,   TransportMode.STATIONARY)
    @Test fun justBelow1_isStationary() = check(0.9,   TransportMode.STATIONARY)
    @Test fun exactly1_isWalking()      = check(1.0,   TransportMode.WALKING)
    @Test fun justAbove1_isWalking()    = check(1.1,   TransportMode.WALKING)
    @Test fun justBelow6_isWalking()    = check(5.9,   TransportMode.WALKING)
    @Test fun exactly6_isCycling()      = check(6.0,   TransportMode.CYCLING)
    @Test fun justAbove6_isCycling()    = check(6.1,   TransportMode.CYCLING)
    @Test fun justBelow20_isCycling()   = check(19.9,  TransportMode.CYCLING)
    @Test fun exactly20_isDriving()     = check(20.0,  TransportMode.DRIVING)
    @Test fun justAbove20_isDriving()   = check(20.1,  TransportMode.DRIVING)
    @Test fun justBelow120_isDriving()  = check(119.9, TransportMode.DRIVING)
    @Test fun exactly120_isFastTransit() = check(120.0, TransportMode.FAST_TRANSIT)
    @Test fun above120_isFastTransit()  = check(120.1, TransportMode.FAST_TRANSIT)
    @Test fun veryHigh_isFastTransit()  = check(999.0, TransportMode.FAST_TRANSIT)

    @Test
    fun negative_speed_treatedAsStationary() {
        // GPS occasionally returns negative speed values; treat as zero
        assertEquals(TransportMode.STATIONARY, TransportClassifier.classify(-5.0))
    }

    private fun check(speedKmh: Double, expected: TransportMode) =
        assertEquals(expected, TransportClassifier.classify(speedKmh))
}
