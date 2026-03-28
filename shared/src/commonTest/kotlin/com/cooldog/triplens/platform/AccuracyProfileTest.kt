package com.cooldog.triplens.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JVM unit tests for [AccuracyProfile] — verifies all interval and priority values
 * match the TDD §4.2 specification. No Android emulator required.
 */
class AccuracyProfileTest {

    @Test
    fun `STANDARD profile has correct moving interval`() {
        assertEquals(8_000L, AccuracyProfile.STANDARD.movingIntervalMs)
    }

    @Test
    fun `STANDARD profile has correct stationary interval`() {
        assertEquals(60_000L, AccuracyProfile.STANDARD.stationaryIntervalMs)
    }

    @Test
    fun `STANDARD profile uses HIGH_ACCURACY priority`() {
        assertEquals(LocationPriority.HIGH_ACCURACY, AccuracyProfile.STANDARD.priority)
    }

    @Test
    fun `HIGH profile has correct moving interval`() {
        assertEquals(4_000L, AccuracyProfile.HIGH.movingIntervalMs)
    }

    @Test
    fun `HIGH profile has correct stationary interval`() {
        assertEquals(4_000L, AccuracyProfile.HIGH.stationaryIntervalMs)
    }

    @Test
    fun `HIGH profile uses HIGH_ACCURACY priority`() {
        assertEquals(LocationPriority.HIGH_ACCURACY, AccuracyProfile.HIGH.priority)
    }

    @Test
    fun `BATTERY_SAVER profile has correct moving interval`() {
        assertEquals(45_000L, AccuracyProfile.BATTERY_SAVER.movingIntervalMs)
    }

    @Test
    fun `BATTERY_SAVER profile has correct stationary interval`() {
        assertEquals(60_000L, AccuracyProfile.BATTERY_SAVER.stationaryIntervalMs)
    }

    @Test
    fun `BATTERY_SAVER profile uses BALANCED priority`() {
        assertEquals(LocationPriority.BALANCED, AccuracyProfile.BATTERY_SAVER.priority)
    }

    @Test
    fun `BATTERY_SAVER priority is lower than STANDARD and HIGH`() {
        // BALANCED is a lower-accuracy setting than HIGH_ACCURACY; they must differ.
        assertNotEquals(
            LocationPriority.HIGH_ACCURACY,
            AccuracyProfile.BATTERY_SAVER.priority,
            "BATTERY_SAVER should NOT use HIGH_ACCURACY priority"
        )
    }

    @Test
    fun `all three profiles are distinct enum values`() {
        val profiles = AccuracyProfile.entries
        assertTrue(profiles.contains(AccuracyProfile.STANDARD))
        assertTrue(profiles.contains(AccuracyProfile.HIGH))
        assertTrue(profiles.contains(AccuracyProfile.BATTERY_SAVER))
        assertEquals(3, profiles.size)
    }
}
