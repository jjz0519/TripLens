package com.cooldog.triplens.export

import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.SessionStatus
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.model.TripGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexJsonBuilderTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private val group = TripGroup(
        id        = "g1",
        name      = "Tokyo 2024",
        createdAt = 1_705_312_800_000L, // 2024-01-15T09:00:00Z
        updatedAt = 1_705_316_400_000L  // 2024-01-15T10:00:00Z
    )

    private val session = Session(
        id        = "s1",
        groupId   = "g1",
        name      = "Day 1",
        startTime = 1_705_312_800_000L,
        endTime   = 1_705_316_400_000L, // 3600s duration
        status    = SessionStatus.COMPLETED
    )

    private fun makePoint(
        id: Long, timestamp: Long, lat: Double, lon: Double,
        alt: Double? = null, speed: Float? = 1.5f,
        mode: TransportMode = TransportMode.WALKING, isAutoPaused: Boolean = false
    ) = TrackPoint(
        id            = id,
        sessionId     = "s1",
        timestamp     = timestamp,
        latitude      = lat,
        longitude     = lon,
        altitude      = alt,
        accuracy      = 5.0f,
        speed         = speed,
        transportMode = mode,
        isAutoPaused  = isAutoPaused
    )

    private val threePoints = listOf(
        makePoint(1, 1_705_312_805_000L, 35.68921, 139.69171, alt = 45.2),
        makePoint(2, 1_705_312_810_000L, 35.68930, 139.69180, alt = 45.3),
        makePoint(3, 1_705_312_815_000L, 35.68940, 139.69190, alt = 45.4)
    )

    private fun build(
        sessions: List<Session> = listOf(session),
        pointsBySession: Map<String, List<TrackPoint>> = mapOf("s1" to threePoints),
        notesBySession: Map<String, List<Note>> = emptyMap(),
        nowMs: Long = 1_705_316_400_000L
    ): JsonObject {
        val json = IndexJsonBuilder.build(group, sessions, pointsBySession, notesBySession, nowMs)
        return Json.parseToJsonElement(json).jsonObject
    }

    // ------------------------------------------------------------------
    // Schema version and top-level structure
    // ------------------------------------------------------------------

    @Test
    fun schemaVersion_isOne() {
        val root = build()
        assertEquals(1, root["schema_version"]!!.jsonPrimitive.int)
    }

    @Test
    fun exportedAt_isIso8601UtcString() {
        // 1705316400000 ms = 2024-01-15T11:00:00Z (UTC)
        val root = build(nowMs = 1_705_316_400_000L)
        val exportedAt = root["exported_at"]!!.jsonPrimitive.content
        assertEquals("2024-01-15T11:00:00Z", exportedAt)
    }

    // ------------------------------------------------------------------
    // Compact track key names
    // ------------------------------------------------------------------

    @Test
    fun trackPoints_useCompactKeyNames() {
        val root     = build()
        val sessions = root["group"]!!.jsonObject["sessions"]!!.jsonArray
        val tracks   = sessions[0].jsonObject["tracks"]!!.jsonArray
        val firstPt  = tracks[0].jsonObject

        // Compact keys must be present
        assertNotNull(firstPt["t"],   "Compact key 't' must be present")
        assertNotNull(firstPt["lat"], "Compact key 'lat' must be present")
        assertNotNull(firstPt["lng"], "Compact key 'lng' must be present")
        assertNotNull(firstPt["acc"], "Compact key 'acc' must be present")
        assertNotNull(firstPt["mode"],"Compact key 'mode' must be present")

        // Long-form keys must NOT be present
        assertNull(firstPt["timestamp"],  "Long-form key 'timestamp' must not appear")
        assertNull(firstPt["latitude"],   "Long-form key 'latitude' must not appear")
        assertNull(firstPt["longitude"],  "Long-form key 'longitude' must not appear")
        assertNull(firstPt["accuracy"],   "Long-form key 'accuracy' must not appear")
        assertNull(firstPt["transportMode"], "Long-form key 'transportMode' must not appear")
    }

    @Test
    fun trackPoints_timestampIsEpochMillisLong() {
        val root     = build()
        val tracks   = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["tracks"]!!.jsonArray
        val t = tracks[0].jsonObject["t"]!!.jsonPrimitive.long
        assertEquals(1_705_312_805_000L, t,
            "Track timestamp 't' must be epoch millis, not an ISO string")
    }

    @Test
    fun trackPoints_modeIsLowercase() {
        val root  = build()
        val tracks = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["tracks"]!!.jsonArray
        val mode = tracks[0].jsonObject["mode"]!!.jsonPrimitive.content
        assertEquals("walking", mode)
    }

    @Test
    fun trackPoints_nullAltitude_altKeyAbsent() {
        val points = listOf(makePoint(1, 1_705_312_805_000L, 35.0, 139.0, alt = null))
        val root   = build(pointsBySession = mapOf("s1" to points))
        val tracks = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["tracks"]!!.jsonArray
        assertNull(tracks[0].jsonObject["alt"],
            "Null altitude must not produce an 'alt' key (encodeDefaults = false)")
    }

    // ------------------------------------------------------------------
    // Non-track timestamps are ISO 8601 strings
    // ------------------------------------------------------------------

    @Test
    fun groupTimestamps_areIso8601Strings() {
        // createdAt = 1705312800000 = 2024-01-15T10:00:00Z; updatedAt = 1705316400000 = 2024-01-15T11:00:00Z
        val groupObj = build()["group"]!!.jsonObject
        assertEquals("2024-01-15T10:00:00Z", groupObj["created_at"]!!.jsonPrimitive.content)
        assertEquals("2024-01-15T11:00:00Z", groupObj["updated_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionTimestamps_areIso8601Strings() {
        // startTime = 1705312800000 = 2024-01-15T10:00:00Z; endTime = 1705316400000 = 2024-01-15T11:00:00Z
        val sessionObj = build()["group"]!!.jsonObject["sessions"]!!.jsonArray[0].jsonObject
        assertEquals("2024-01-15T10:00:00Z", sessionObj["start_time"]!!.jsonPrimitive.content)
        assertEquals("2024-01-15T11:00:00Z", sessionObj["end_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun session_nullEndTime_endTimeKeyAbsent() {
        val openSession = session.copy(endTime = null, status = SessionStatus.RECORDING)
        val root = build(sessions = listOf(openSession))
        val sessionObj = root["group"]!!.jsonObject["sessions"]!!.jsonArray[0].jsonObject
        assertNull(sessionObj["end_time"], "Null endTime must not produce an 'end_time' key")
    }

    // ------------------------------------------------------------------
    // track_summary distance calculation
    // ------------------------------------------------------------------

    @Test
    fun trackSummary_distanceMeters_isHaversineSum() {
        val root    = build()
        val summary = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["track_summary"]!!.jsonObject

        // Manually compute expected distance for three consecutive points
        val d1 = IndexJsonBuilder.haversineMeters(35.68921, 139.69171, 35.68930, 139.69180)
        val d2 = IndexJsonBuilder.haversineMeters(35.68930, 139.69180, 35.68940, 139.69190)
        val expected = d1 + d2

        val actual = summary["distance_meters"]!!.jsonPrimitive.content.toDouble()
        // Allow 0.01 m tolerance for floating-point representation
        assertTrue(abs(expected - actual) < 0.01,
            "distance_meters=$actual should be haversine sum≈$expected")
    }

    @Test
    fun trackSummary_autoPausedPoints_excludedFromDistance() {
        val points = listOf(
            makePoint(1, 1_705_312_800_000L, 35.0,  139.0, isAutoPaused = false),
            makePoint(2, 1_705_312_860_000L, 35.01, 139.0, isAutoPaused = true),  // paused
            makePoint(3, 1_705_312_920_000L, 35.02, 139.0, isAutoPaused = false)
        )
        val root    = build(pointsBySession = mapOf("s1" to points))
        val summary = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["track_summary"]!!.jsonObject

        // Only points 1 and 3 are active; distance should be between pt1 and pt3 only.
        // If paused points were included the distance would be higher (3 consecutive segments).
        val activeDistance = IndexJsonBuilder.haversineMeters(35.0, 139.0, 35.02, 139.0)
        val actual = summary["distance_meters"]!!.jsonPrimitive.content.toDouble()
        assertTrue(abs(activeDistance - actual) < 0.01,
            "distance_meters=$actual should exclude auto-paused point, expected≈$activeDistance")
    }

    @Test
    fun trackSummary_pointCount_includesAutoPausedPoints() {
        val points = listOf(
            makePoint(1, 1_705_312_800_000L, 35.0, 139.0, isAutoPaused = false),
            makePoint(2, 1_705_312_860_000L, 35.0, 139.0, isAutoPaused = true)
        )
        val root    = build(pointsBySession = mapOf("s1" to points))
        val summary = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["track_summary"]!!.jsonObject

        assertEquals(2, summary["point_count"]!!.jsonPrimitive.int,
            "point_count must include auto-paused points")
    }

    @Test
    fun trackSummary_durationSeconds_isEndMinusStartDividedBy1000() {
        val root    = build()
        val summary = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["track_summary"]!!.jsonObject

        // session endTime - startTime = 1_705_316_400_000 - 1_705_312_800_000 = 3_600_000 ms = 3600 s
        assertEquals(3600L, summary["duration_seconds"]!!.jsonPrimitive.long)
    }

    // ------------------------------------------------------------------
    // Notes
    // ------------------------------------------------------------------

    @Test
    fun voiceNote_hasAudioFileField_withRelativePath() {
        val note = Note(
            id              = "n1",
            sessionId       = "s1",
            type            = NoteType.VOICE,
            content         = null,
            audioFilename   = "note_abc123.m4a",
            durationSeconds = 45,
            createdAt       = 1_705_312_810_000L,
            latitude        = 35.689,
            longitude       = 139.692
        )
        val root      = build(notesBySession = mapOf("s1" to listOf(note)))
        val noteObj   = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["notes"]!!.jsonArray[0].jsonObject

        assertEquals("notes/note_abc123.m4a", noteObj["audio_file"]!!.jsonPrimitive.content,
            "audio_file must be a relative path under 'notes/'")
        assertNull(noteObj["content"], "Voice note must not have a 'content' field")
        assertEquals(45, noteObj["duration_seconds"]!!.jsonPrimitive.int)
    }

    @Test
    fun textNote_hasContentField_noAudioFile() {
        val note = Note(
            id              = "n2",
            sessionId       = "s1",
            type            = NoteType.TEXT,
            content         = "Great view!",
            audioFilename   = null,
            durationSeconds = null,
            createdAt       = 1_705_312_820_000L,
            latitude        = 35.690,
            longitude       = 139.692
        )
        val root    = build(notesBySession = mapOf("s1" to listOf(note)))
        val noteObj = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["notes"]!!.jsonArray[0].jsonObject

        assertEquals("Great view!", noteObj["content"]!!.jsonPrimitive.content)
        assertNull(noteObj["audio_file"], "Text note must not have an 'audio_file' field")
    }

    @Test
    fun noteTimestamp_isIso8601String() {
        val note = Note(
            id = "n1", sessionId = "s1", type = NoteType.TEXT,
            content = "x", audioFilename = null, durationSeconds = null,
            createdAt = 1_705_312_810_000L, // 1705312810000 = 2024-01-15T10:00:10Z
            latitude = 0.0, longitude = 0.0
        )
        val root    = build(notesBySession = mapOf("s1" to listOf(note)))
        val noteObj = root["group"]!!.jsonObject["sessions"]!!
            .jsonArray[0].jsonObject["notes"]!!.jsonArray[0].jsonObject

        assertEquals("2024-01-15T10:00:10Z", noteObj["created_at"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Haversine helper
    // ------------------------------------------------------------------

    @Test
    fun haversineMeters_knownDistance_equatorOneDegreeLon() {
        // At the equator, 1° longitude ≈ 111 320 m (±0.5% for haversine)
        val dist = IndexJsonBuilder.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertTrue(dist in 111_000.0..111_700.0,
            "1° longitude at equator should be ~111 320 m, got $dist")
    }

    @Test
    fun haversineMeters_samePoint_isZero() {
        assertEquals(0.0, IndexJsonBuilder.haversineMeters(35.0, 139.0, 35.0, 139.0))
    }

    // ------------------------------------------------------------------
    // Session status
    // ------------------------------------------------------------------

    @Test
    fun sessionStatus_isLowercase() {
        val root       = build()
        val sessionObj = root["group"]!!.jsonObject["sessions"]!!.jsonArray[0].jsonObject
        assertEquals("completed", sessionObj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun multipleSessionsInGroup_allPresent() {
        val session2 = session.copy(id = "s2", name = "Day 2")
        val root = build(
            sessions = listOf(session, session2),
            pointsBySession = mapOf("s1" to threePoints, "s2" to emptyList())
        )
        val sessions = root["group"]!!.jsonObject["sessions"]!!.jsonArray
        assertEquals(2, sessions.size)
        val ids = sessions.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("s1", "s2"), ids)
    }
}
