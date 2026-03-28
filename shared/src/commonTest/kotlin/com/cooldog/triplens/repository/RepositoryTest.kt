package com.cooldog.triplens.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cooldog.triplens.db.TripLensDatabase
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.SessionStatus
import com.cooldog.triplens.model.TransportMode
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {

    private lateinit var db: TripLensDatabase
    private lateinit var tripRepo: TripRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var trackPointRepo: TrackPointRepository
    private lateinit var mediaRefRepo: MediaRefRepository
    private lateinit var noteRepo: NoteRepository

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TripLensDatabase.Schema.create(driver)
        db = TripLensDatabase(driver)
        tripRepo = TripRepository(db)
        sessionRepo = SessionRepository(db)
        trackPointRepo = TrackPointRepository(db)
        mediaRefRepo = MediaRefRepository(db)
        noteRepo = NoteRepository(db)
    }

    // --- TripRepository ---

    @Test
    fun tripRepository_createAndGetAll() = runTest {
        tripRepo.createGroup("id1", "Wellington", 1_000L)
        tripRepo.createGroup("id2", "Tokyo", 2_000L)
        val all = tripRepo.getAllGroups()
        assertEquals(2, all.size)
    }

    @Test
    fun tripRepository_rename() = runTest {
        tripRepo.createGroup("id1", "Old Name", 1_000L)
        tripRepo.renameGroup("id1", "New Name", 2_000L)
        val group = tripRepo.getGroupById("id1")
        assertNotNull(group)
        assertEquals("New Name", group.name)
    }

    @Test
    fun tripRepository_delete_cascadesToSessions() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
        tripRepo.deleteGroup("g1")
        val sessions = sessionRepo.getSessionsByGroup("g1")
        assertTrue(sessions.isEmpty())
    }

    // --- SessionRepository ---

    @Test
    fun sessionRepository_getActiveSession_nullWhenNoneRecording() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
        sessionRepo.completeSession("s1", 3_000L)
        assertNull(sessionRepo.getActiveSession())
    }

    @Test
    fun sessionRepository_getActiveSession_returnsRecordingSession() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
        val active = sessionRepo.getActiveSession()
        assertNotNull(active)
        assertEquals("s1", active.id)
        assertEquals(SessionStatus.RECORDING, active.status)
    }

    @Test
    fun sessionRepository_markInterrupted() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
        sessionRepo.markInterrupted("s1")
        val session = sessionRepo.getSessionById("s1")
        assertEquals(SessionStatus.INTERRUPTED, session?.status)
    }

    // --- TrackPointRepository ---

    @Test
    fun trackPointRepository_insertBatch_allPersistedInSingleTransaction() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)

        // Build 10 points
        val points = (0 until 10).map { i ->
            TrackPointInsert(
                sessionId = "s1",
                timestamp = 3_000L + i * 1_000L,
                latitude = -41.28 + i * 0.001,
                longitude = 174.77,
                altitude = null,
                accuracy = 8.5f,
                speed = 1.5f,
                transportMode = TransportMode.WALKING
            )
        }

        trackPointRepo.insertBatch(points)

        val stored = trackPointRepo.getBySession("s1")
        assertEquals(10, stored.size)
    }

    // --- MediaRefRepository ---

    @Test
    fun mediaRefRepository_insertIfNotExists_deduplicatesByUri() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)

        mediaRefRepo.insertIfNotExists(
            "m1", "s1", "photo", "phone_gallery",
            "content://media/1", "IMG.jpg", 3_000L
        )
        mediaRefRepo.insertIfNotExists(
            "m2", "s1", "photo", "phone_gallery",
            "content://media/1", "IMG.jpg", 3_000L  // same URI
        )

        val refs = mediaRefRepo.getBySession("s1")
        assertEquals(1, refs.size)
        assertEquals("m1", refs[0].id)  // First insert wins
    }

    // --- NoteRepository ---

    @Test
    fun noteRepository_createTextAndVoice_queryBySession() = runTest {
        tripRepo.createGroup("g1", "Trip", 1_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)

        noteRepo.createTextNote("n1", "s1", "Great view!", 3_000L, -41.28, 174.77)
        noteRepo.createVoiceNote("n2", "s1", "note_n2.m4a", 45, 4_000L, -41.29, 174.78)

        val notes = noteRepo.getBySession("s1")
        assertEquals(2, notes.size)
        assertEquals(NoteType.TEXT, notes[0].type)
        assertEquals(NoteType.VOICE, notes[1].type)
        assertEquals("note_n2.m4a", notes[1].audioFilename)
    }
}
