package com.cooldog.triplens.export

import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.SessionStatus
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpxWriterTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private val session = Session(
        id        = "s1",
        groupId   = "g1",
        name      = "Day 1",
        startTime = 1_705_312_800_000L, // 2024-01-15T09:00:00Z
        endTime   = 1_705_316_400_000L,
        status    = SessionStatus.COMPLETED
    )

    private fun makePoint(
        id: Long,
        timestamp: Long,
        lat: Double,
        lon: Double,
        alt: Double?,
        speed: Float?,
        mode: TransportMode
    ) = TrackPoint(
        id            = id,
        sessionId     = "s1",
        timestamp     = timestamp,
        latitude      = lat,
        longitude     = lon,
        altitude      = alt,
        accuracy      = 8.5f,
        speed         = speed,
        transportMode = mode,
        isAutoPaused  = false
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun singleSession_threePoints_validGpxWithCorrectElements() {
        val points = listOf(
            makePoint(1, 1_705_312_805_000L, 35.68921, 139.69171, 45.2, 1.5f, TransportMode.WALKING),
            makePoint(2, 1_705_312_810_000L, 35.68930, 139.69180, 45.3, 1.6f, TransportMode.WALKING),
            makePoint(3, 1_705_312_815_000L, 35.68940, 139.69190, 45.4, 1.7f, TransportMode.WALKING)
        )

        val gpx = GpxWriter.write(session, points)

        // XML declaration and GPX root
        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""),
            "Should start with XML declaration")
        assertContains(gpx, """<gpx version="1.1"""")
        assertContains(gpx, """xmlns="http://www.topografix.com/GPX/1/1"""")
        assertContains(gpx, """xmlns:triplens="https://triplens.app/gpx/extensions/v1"""")

        // Metadata
        assertContains(gpx, "<name>Day 1</name>")
        assertContains(gpx, "<time>2024-01-15T10:00:00Z</time>")

        // Track points: lat/lon attributes
        assertContains(gpx, """lat="35.68921"""")
        assertContains(gpx, """lon="139.69171"""")

        // Elevation
        assertContains(gpx, "<ele>45.2</ele>")

        // Point timestamps
        assertContains(gpx, "<time>2024-01-15T10:00:05Z</time>")

        // Extensions
        assertContains(gpx, "<triplens:speed>1.5</triplens:speed>")
        assertContains(gpx, "<triplens:accuracy>8.5</triplens:accuracy>")
        assertContains(gpx, "<triplens:mode>walking</triplens:mode>")

        // Three trkpt elements
        assertEquals(3, gpx.split("<trkpt").size - 1, "Should have exactly 3 trkpt elements")

        // Closes properly
        assertTrue(gpx.trimEnd().endsWith("</gpx>"), "Should end with </gpx>")
    }

    @Test
    fun sessionWithNoPoints_validGpxWithEmptyTrkseg() {
        val gpx = GpxWriter.write(session, emptyList())

        assertContains(gpx, "<gpx version=\"1.1\"")
        assertContains(gpx, "<trkseg>")
        assertContains(gpx, "</trkseg>")
        // No track points inside the segment
        assertFalse(gpx.contains("<trkpt"), "Empty session should have no trkpt elements")
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun nullAltitude_eleElementOmitted() {
        val points = listOf(
            makePoint(1, 1_705_312_805_000L, 35.68921, 139.69171, alt = null, speed = 1.5f, mode = TransportMode.WALKING)
        )

        val gpx = GpxWriter.write(session, points)

        assertFalse(gpx.contains("<ele>"), "Null altitude must not produce an <ele> element")
    }

    @Test
    fun nullSpeed_triplenSpeedExtensionOmitted() {
        val points = listOf(
            makePoint(1, 1_705_312_805_000L, 35.68921, 139.69171, alt = 45.0, speed = null, mode = TransportMode.WALKING)
        )

        val gpx = GpxWriter.write(session, points)

        assertFalse(gpx.contains("<triplens:speed>"),
            "Null speed must not produce a <triplens:speed> extension")
        // Accuracy and mode are always present
        assertContains(gpx, "<triplens:accuracy>")
        assertContains(gpx, "<triplens:mode>")
    }

    @Test
    fun allTransportModes_writtenAsLowercase() {
        TransportMode.entries.forEach { mode ->
            val points = listOf(
                makePoint(1, 1_705_312_805_000L, 0.0, 0.0, null, null, mode)
            )
            val gpx = GpxWriter.write(session, points)
            val expectedMode = mode.name.lowercase()
            assertContains(gpx, "<triplens:mode>$expectedMode</triplens:mode>",
                message = "Mode ${mode.name} should appear as '$expectedMode' in GPX")
        }
    }

    @Test
    fun sessionNameWithXmlSpecialChars_isEscaped() {
        val specialSession = session.copy(name = "Day 1 <Test> & \"Fun\"")
        val gpx = GpxWriter.write(specialSession, emptyList())

        assertContains(gpx, "Day 1 &lt;Test&gt; &amp; &quot;Fun&quot;",
            message = "XML special characters in session name must be escaped")
        assertFalse(gpx.contains("<Test>"),
            "Unescaped < must not appear in the output")
    }

    // ------------------------------------------------------------------
    // Helper function tests
    // ------------------------------------------------------------------

    @Test
    fun formatIso8601_epochZero_returnsUnixEpochString() {
        assertEquals("1970-01-01T00:00:00Z", GpxWriter.formatIso8601(0L))
    }

    @Test
    fun formatIso8601_knownTimestamp() {
        // 1705312800000 ms = 2024-01-15T10:00:00Z (UTC)
        assertEquals("2024-01-15T10:00:00Z", GpxWriter.formatIso8601(1_705_312_800_000L))
    }

    @Test
    fun escapeXml_replacesAllFiveSpecialChars() {
        val input    = """<"&">test'"""
        val expected = """&lt;&quot;&amp;&quot;&gt;test&apos;"""
        assertEquals(expected, GpxWriter.escapeXml(input))
    }
}
