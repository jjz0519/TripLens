package com.cooldog.triplens.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun tripGroup_roundTrip() {
        val group = TripGroup(
            id = "abc",
            name = "Wellington",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_001_000L
        )
        val encoded = json.encodeToString(group)
        assertEquals(group, json.decodeFromString(encoded))
    }

    @Test
    fun session_roundTrip_withNullEndTime() {
        val session = Session(
            id = "s1",
            groupId = "g1",
            name = "Day 1",
            startTime = 1_700_000_000_000L,
            endTime = null,
            status = SessionStatus.RECORDING
        )
        val encoded = json.encodeToString(session)
        val decoded: Session = json.decodeFromString(encoded)
        assertEquals(session, decoded)
        assertEquals(null, decoded.endTime)
    }

    @Test
    fun trackPoint_roundTrip_withNullOptionals() {
        val point = TrackPoint(
            id = 1L,
            sessionId = "s1",
            timestamp = 1_700_000_000_000L,
            latitude = -41.2865,
            longitude = 174.7762,
            altitude = null,
            accuracy = 8.5f,
            speed = null,
            transportMode = TransportMode.WALKING
        )
        val encoded = json.encodeToString(point)
        assertEquals(point, json.decodeFromString(encoded))
    }

    @Test
    fun mediaReference_roundTrip() {
        val ref = MediaReference(
            id = "m1",
            sessionId = "s1",
            type = MediaType.PHOTO,
            source = MediaSource.PHONE_GALLERY,
            contentUri = "content://media/external/images/1234",
            originalFilename = "IMG_001.jpg",
            capturedAt = 1_700_000_000_000L,
            timestampOffset = 0,
            originalLat = -41.29,
            originalLng = 174.78,
            inferredLat = null,
            inferredLng = null,
            locationSource = LocationSource.EXIF,
            matchedSessionId = null,
            matchedTrackpointId = null
        )
        val encoded = json.encodeToString(ref)
        assertEquals(ref, json.decodeFromString(encoded))
    }

    @Test
    fun note_roundTrip_voiceNote() {
        val note = Note(
            id = "n1",
            sessionId = "s1",
            type = NoteType.VOICE,
            content = null,
            audioFilename = "note_n1.m4a",
            durationSeconds = 45,
            createdAt = 1_700_000_000_000L,
            latitude = -41.2865,
            longitude = 174.7762
        )
        val encoded = json.encodeToString(note)
        assertEquals(note, json.decodeFromString(encoded))
    }

    @Test
    fun transportMode_serializedValues_areLowercase() {
        // DB CHECK constraints use lowercase: 'walking' not 'WALKING'
        assertEquals("\"walking\"", json.encodeToString(TransportMode.WALKING))
        assertEquals("\"fast_transit\"", json.encodeToString(TransportMode.FAST_TRANSIT))
        assertEquals("\"stationary\"", json.encodeToString(TransportMode.STATIONARY))
    }

    @Test
    fun sessionStatus_serializedValues_areLowercase() {
        assertEquals("\"recording\"", json.encodeToString(SessionStatus.RECORDING))
        assertEquals("\"completed\"", json.encodeToString(SessionStatus.COMPLETED))
        assertEquals("\"interrupted\"", json.encodeToString(SessionStatus.INTERRUPTED))
    }
}
