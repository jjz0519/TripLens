package com.cooldog.triplens.domain

import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentSmootherTest {

    // Helper: build a minimal TrackPoint. IDs are sequential, timestamps 1s apart.
    private fun points(vararg modes: TransportMode): List<TrackPoint> =
        modes.mapIndexed { i, mode ->
            TrackPoint(
                id = i.toLong(),
                sessionId = "s1",
                timestamp = i * 1_000L,
                latitude = -41.28 + i * 0.0001,
                longitude = 174.77,
                altitude = null,
                accuracy = 8f,
                speed = null,
                transportMode = mode
            )
        }

    @Test
    fun emptyList_returnsEmpty() {
        assertTrue(SegmentSmoother.smooth(emptyList(), minSegmentPoints = 3).isEmpty())
    }

    @Test
    fun singlePoint_returnsOneSegment() {
        val result = SegmentSmoother.smooth(points(TransportMode.WALKING), minSegmentPoints = 3)
        assertEquals(1, result.size)
        assertEquals(TransportMode.WALKING, result[0].mode)
    }

    @Test
    fun allSameMode_returnsOneSegment() {
        val input = points(
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.DRIVING, TransportMode.DRIVING
        )
        val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
        assertEquals(1, result.size)
        assertEquals(TransportMode.DRIVING, result[0].mode)
        assertEquals(5, result[0].pointCount)
    }

    @Test
    fun shortNoise_betweenSameMode_isAbsorbed() {
        // 5 driving, 1 cycling (noise), 5 driving → should produce 1 driving segment
        val input = points(
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.CYCLING,  // 1 point noise — less than minSegmentPoints=3
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.DRIVING, TransportMode.DRIVING
        )
        val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
        assertEquals(1, result.size)
        assertEquals(TransportMode.DRIVING, result[0].mode)
    }

    @Test
    fun shortNoise_atStart_isKept() {
        // 1 cycling at start, then 5 driving — no same-mode neighbor on left, keep as-is
        val input = points(
            TransportMode.CYCLING,
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.DRIVING, TransportMode.DRIVING
        )
        val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
        assertEquals(2, result.size)
        assertEquals(TransportMode.CYCLING, result[0].mode)
        assertEquals(TransportMode.DRIVING, result[1].mode)
    }

    @Test
    fun shortNoise_atEnd_isKept() {
        // 5 driving then 1 cycling at end — no same-mode neighbor on right, keep as-is
        val input = points(
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.CYCLING
        )
        val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
        assertEquals(2, result.size)
        assertEquals(TransportMode.DRIVING, result[0].mode)
        assertEquals(TransportMode.CYCLING, result[1].mode)
    }

    @Test
    fun alternatingLongSegments_noSmoothing() {
        // 4 walking, 4 driving, 4 walking — all exceed minSegmentPoints, no absorption
        val input = points(
            TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING,
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
            TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING
        )
        val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
        assertEquals(3, result.size)
        assertEquals(TransportMode.WALKING, result[0].mode)
        assertEquals(TransportMode.DRIVING, result[1].mode)
        assertEquals(TransportMode.WALKING, result[2].mode)
    }

    @Test
    fun segment_pointCount_matchesExpected() {
        val input = points(
            TransportMode.WALKING, TransportMode.WALKING,
            TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING
        )
        val result = SegmentSmoother.smooth(input, minSegmentPoints = 1)
        assertEquals(2, result[0].pointCount)
        assertEquals(3, result[1].pointCount)
    }
}
