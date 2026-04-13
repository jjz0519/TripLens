package com.cooldog.triplens.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.db.TripLensDatabase
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TrackPointInsert
import com.cooldog.triplens.repository.TrackPointRepository
import com.cooldog.triplens.repository.TripRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TAG = "TripLens/ExportRoundTripTest"

/**
 * Full-pipeline instrumented test for [ExportUseCase] + [AndroidFileSystem].
 *
 * ## Why instrumented (not unit)
 * [AndroidFileSystem] uses [android.content.Context] to resolve real filesystem paths
 * (filesDir, cacheDir). [AndroidSqliteDriver] uses the Android SQLite backend. Neither
 * can be exercised in a JVM-only unit test.
 *
 * ## What is verified
 * 1. The exported file exists at the returned path and is a valid ZIP.
 * 2. `index.json` inside the archive contains the correct session, track point, and note counts.
 * 3. GPX files are present for each session.
 */
@RunWith(AndroidJUnit4::class)
class ExportRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var tripRepo: TripRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var trackPointRepo: TrackPointRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var mediaRefRepo: MediaRefRepository
    private lateinit var fileSystem: AndroidFileSystem

    /** Paths cleaned up after each test to avoid polluting the device's exports dir. */
    private val cleanupPaths = mutableListOf<String>()

    @Before
    fun setUp() {
        // Use a uniquely named on-disk database so tests don't share state with each other
        // or with the production database ("triplens.db"). AndroidSqliteDriver calls
        // TripLensDatabase.Schema.create() automatically when the database file does not exist.
        val dbName = "triplens_roundtrip_test_${System.currentTimeMillis()}.db"
        val driver = AndroidSqliteDriver(TripLensDatabase.Schema, context, dbName)
        db = AppDatabase(driver)
        tripRepo       = TripRepository(db)
        sessionRepo    = SessionRepository(db)
        trackPointRepo = TrackPointRepository(db)
        noteRepo       = NoteRepository(db)
        mediaRefRepo   = MediaRefRepository(db)
        fileSystem     = AndroidFileSystem(context)

        // Clean the exports dir before the test so stale files don't interfere.
        File(context.filesDir, "exports").deleteRecursively()
    }

    @After
    fun tearDown() {
        // Remove exported archives to avoid leaving test data on the device.
        cleanupPaths.forEach { File(it).delete() }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    @Test
    fun exportRoundTrip_zipExistsAndIndexJsonParseable() = runTest {
        // ── Seed ──────────────────────────────────────────────────────────────────
        val groupId = "g1"
        val sessionId = "s1"
        val now = 1_705_312_800_000L  // 2024-01-15T10:00:00Z

        tripRepo.createGroup(groupId, "Round-Trip Test Trip", now)
        sessionRepo.createSession(sessionId, groupId, "Session 1", now)
        sessionRepo.completeSession(sessionId, now + 3_600_000L)

        // 3 track points at walking speed, ~100 m apart.
        trackPointRepo.insertBatch(listOf(
            TrackPointInsert(sessionId, now + 10_000L, 35.68921, 139.69171, 45.0, 5.0f, 1.5f, TransportMode.WALKING),
            TrackPointInsert(sessionId, now + 20_000L, 35.68940, 139.69180, 45.2, 5.0f, 1.6f, TransportMode.WALKING),
            TrackPointInsert(sessionId, now + 30_000L, 35.68960, 139.69190, 45.4, 5.0f, 1.5f, TransportMode.WALKING),
        ))

        // 1 text note.
        noteRepo.createTextNote("note1", sessionId, "Hello round-trip", now + 15_000L, 35.68930, 139.69175)

        // ── Export ────────────────────────────────────────────────────────────────
        val useCase = ExportUseCase(
            tripRepo       = tripRepo,
            sessionRepo    = sessionRepo,
            trackPointRepo = trackPointRepo,
            noteRepo       = noteRepo,
            mediaRefRepo   = mediaRefRepo,
            fileSystem     = fileSystem,
        )
        val result = useCase.export(groupId, now + 3_700_000L)
        cleanupPaths += result.path

        // ── Assert: zip file exists ───────────────────────────────────────────────
        val archiveFile = File(result.path)
        assertTrue(archiveFile.exists(), "Exported archive must exist at ${result.path}")
        assertTrue(result.sizeBytes > 0, "Archive size must be > 0")

        // ── Assert: zip is readable and contains expected entries ─────────────────
        val zipEntries = ZipFile(archiveFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertTrue(
            zipEntries.any { it.endsWith("index.json") },
            "Archive must contain index.json; entries: $zipEntries",
        )
        assertTrue(
            zipEntries.any { it.contains("tracks/") && it.endsWith(".gpx") },
            "Archive must contain at least one GPX file; entries: $zipEntries",
        )

        // ── Assert: index.json has correct counts ─────────────────────────────────
        val indexJson = ZipFile(archiveFile).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith("index.json") }
            zip.getInputStream(entry).bufferedReader().readText()
        }

        val root: JsonObject = Json.parseToJsonElement(indexJson).jsonObject
        val sessions = root["sessions"]?.jsonArray
        assertNotNull(sessions, "index.json must have a 'sessions' array")
        assertEquals(1, sessions.size, "Expected 1 session in index.json, got ${sessions.size}")

        val session = sessions[0].jsonObject
        val trackPoints = session["tracks"]?.jsonArray
        assertNotNull(trackPoints, "Session must have a 'tracks' array")
        assertEquals(
            3, trackPoints.size,
            "Expected 3 track points in index.json, got ${trackPoints.size}",
        )

        val notes = session["notes"]?.jsonArray
        assertNotNull(notes, "Session must have a 'notes' array")
        assertEquals(1, notes.size, "Expected 1 note in index.json, got ${notes.size}")

        val note = notes[0].jsonObject
        assertEquals(
            "text",
            note["type"]?.jsonPrimitive?.content,
            "Note type must be 'text'",
        )
    }
}
