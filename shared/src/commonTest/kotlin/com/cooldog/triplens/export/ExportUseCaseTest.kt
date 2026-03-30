package com.cooldog.triplens.export

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TrackPointInsert
import com.cooldog.triplens.repository.TrackPointRepository
import com.cooldog.triplens.repository.TripRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExportUseCaseTest {

    // ------------------------------------------------------------------
    // Database and repositories
    // ------------------------------------------------------------------

    private lateinit var db: AppDatabase
    private lateinit var tripRepo:       TripRepository
    private lateinit var sessionRepo:    SessionRepository
    private lateinit var trackPointRepo: TrackPointRepository
    private lateinit var noteRepo:       NoteRepository
    private lateinit var mediaRefRepo:   MediaRefRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        db             = AppDatabase(driver)
        tripRepo       = TripRepository(db)
        sessionRepo    = SessionRepository(db)
        trackPointRepo = TrackPointRepository(db)
        noteRepo       = NoteRepository(db)
        mediaRefRepo   = MediaRefRepository(db)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun makeUseCase(fileSystem: PlatformFileSystem) = ExportUseCase(
        tripRepo       = tripRepo,
        sessionRepo    = sessionRepo,
        trackPointRepo = trackPointRepo,
        noteRepo       = noteRepo,
        mediaRefRepo   = mediaRefRepo,
        fileSystem     = fileSystem
    )

    private fun seedGroupWithTwoSessionsAndOneVoiceNote(): String {
        tripRepo.createGroup("g1", "Tokyo Trip", 1_705_312_800_000L)
        sessionRepo.createSession("s1", "g1", "Day 1", 1_705_312_800_000L)
        sessionRepo.createSession("s2", "g1", "Day 2", 1_705_399_200_000L)

        trackPointRepo.insertBatch(listOf(
            TrackPointInsert("s1", 1_705_312_810_000L, 35.68921, 139.69171, 45.2, 5.0f, 1.5f, TransportMode.WALKING),
            TrackPointInsert("s1", 1_705_312_820_000L, 35.68930, 139.69180, 45.3, 5.0f, 1.6f, TransportMode.WALKING),
            TrackPointInsert("s2", 1_705_399_210_000L, 35.69000, 139.70000, null, 5.0f, null, TransportMode.STATIONARY)
        ))

        noteRepo.createVoiceNote("n1", "s1", "note_abc.m4a", 30, 1_705_312_815_000L, 35.689, 139.692)

        return "g1"
    }

    // ------------------------------------------------------------------
    // Pipeline step verification via SpyPlatformFileSystem
    // ------------------------------------------------------------------

    @Test
    fun exportPipeline_callsExpectedFileSystemOps() = runTest {
        val spy = SpyPlatformFileSystem()
        val useCase = makeUseCase(spy)
        val groupId = seedGroupWithTwoSessionsAndOneVoiceNote()

        useCase.export(groupId, nowMs = 1_705_400_000_000L)

        // index.json written exactly once
        assertEquals(1, spy.writtenPaths.count { it.endsWith("index.json") },
            "writeText must be called for index.json")

        // GPX written for each session
        assertEquals(1, spy.writtenPaths.count { it.endsWith("session_s1.gpx") },
            "writeText must be called for session_s1.gpx")
        assertEquals(1, spy.writtenPaths.count { it.endsWith("session_s2.gpx") },
            "writeText must be called for session_s2.gpx")

        // Voice note copied
        assertEquals(1, spy.copiedSources.count { it.endsWith("note_abc.m4a") },
            "copy must be called for the voice note")

        // Zip called exactly once
        assertEquals(1, spy.zipCallCount, "zip must be called exactly once")

        // README.txt written
        assertEquals(1, spy.writtenPaths.count { it.endsWith("README.txt") },
            "writeText must be called for README.txt")

        // Temp dir deleted after successful export
        assertTrue(spy.deletedPaths.isNotEmpty(),
            "deleteRecursive must be called to clean up the temp dir")
    }

    @Test
    fun exportPipeline_returnsCorrectResult() = runTest {
        val spy = SpyPlatformFileSystem()
        val useCase = makeUseCase(spy)
        val groupId = seedGroupWithTwoSessionsAndOneVoiceNote()

        val result = useCase.export(groupId, nowMs = 1_705_400_000_000L)

        assertNotNull(result)
        assertTrue(result.path.endsWith(".triplens"),
            "Output path must end with .triplens, got: ${result.path}")
        // SpyPlatformFileSystem.size() returns a fixed value; just check it is non-negative.
        assertTrue(result.sizeBytes >= 0)
    }

    @Test
    fun export_unknownGroupId_throwsIllegalArgumentException() = runTest {
        val useCase = makeUseCase(SpyPlatformFileSystem())

        assertFailsWith<IllegalArgumentException> {
            useCase.export("nonexistent-group-id", nowMs = 1_000L)
        }
    }

    @Test
    fun export_zipThrows_tempDirDeletedAndExportExceptionThrown() = runTest {
        val failingFs = FailOnZipFileSystem()
        val useCase   = makeUseCase(failingFs)

        tripRepo.createGroup("g2", "Fail Trip", 1_000L)
        sessionRepo.createSession("s99", "g2", "Session", 2_000L)

        assertFailsWith<ExportException>("zip failure must be wrapped in ExportException") {
            useCase.export("g2", nowMs = 3_000L)
        }

        // Temp dir must be cleaned up even when zip fails
        assertTrue(failingFs.deleteRecursiveCalled,
            "deleteRecursive must be called when zip throws to clean up the temp dir")
    }

    @Test
    fun archiveName_sanitizesGroupNameAndIncludesDate() = runTest {
        // Group name with spaces and special characters
        tripRepo.createGroup("g3", "My Trip! 2024", 1_705_312_800_000L)
        sessionRepo.createSession("s3", "g3", "Day 1", 1_705_312_810_000L)

        val spy = SpyPlatformFileSystem()
        makeUseCase(spy).export("g3", nowMs = 1_000L)

        // The temp dir name (= archive folder name) must be derived from group name + date
        val tempDirName = spy.createdTempDirName
        assertNotNull(tempDirName, "createTempDir must have been called")
        assertTrue(tempDirName.startsWith("triplens-"),
            "Temp dir name must start with 'triplens-', got: $tempDirName")
        assertTrue(tempDirName.contains("2024-01-15"),
            "Temp dir name must contain the group creation date, got: $tempDirName")
        // Special chars replaced with '-'
        assertTrue(!tempDirName.contains("!"),
            "Special character '!' must be replaced in the archive name")
        assertTrue(!tempDirName.contains(" "),
            "Spaces must be replaced in the archive name")
    }

    @Test
    fun sessionWithNoVoiceNotes_notesDirNotCreated() = runTest {
        tripRepo.createGroup("g4", "Silent Trip", 1_705_312_800_000L)
        sessionRepo.createSession("s4", "g4", "Day 1", 1_705_312_810_000L)
        // Only a text note — no voice files to copy
        noteRepo.createTextNote("n99", "s4", "Just text", 1_705_312_820_000L, 0.0, 0.0)

        val spy = SpyPlatformFileSystem()
        makeUseCase(spy).export("g4", nowMs = 1_000L)

        val notesDirCreated = spy.createdDirs.any { it.endsWith("/notes") || it.endsWith("\\notes") }
        assertTrue(!notesDirCreated,
            "notes/ directory must not be created when there are no voice notes")
        assertTrue(spy.copiedSources.isEmpty(),
            "No files should be copied when there are no voice notes")
    }
}

// ------------------------------------------------------------------
// Test doubles
// ------------------------------------------------------------------

/**
 * Records all file system calls so tests can assert on which operations were performed
 * and in what order.
 */
private class SpyPlatformFileSystem : PlatformFileSystem {
    val writtenPaths    = mutableListOf<String>()
    val copiedSources   = mutableListOf<String>()
    val deletedPaths    = mutableListOf<String>()
    val createdDirs     = mutableListOf<String>()
    var zipCallCount    = 0
    var createdTempDirName: String? = null

    // Tracks the last zip source (= temp dir) to verify delete was called on it
    private var lastTempDir = ""
    // Tracks the last output path for size() calls
    private var lastOutputPath = ""

    override fun createTempDir(name: String): String {
        createdTempDirName = name
        lastTempDir = "/tmp/$name"
        return lastTempDir
    }

    override fun createOutputPath(subdir: String, filename: String): String {
        lastOutputPath = "/output/$subdir/$filename"
        return lastOutputPath
    }

    override fun appPrivatePath(vararg segments: String) = "/private/${segments.joinToString("/")}"

    override fun writeText(filePath: String, text: String) {
        writtenPaths += filePath
    }

    override fun createDir(dirPath: String) {
        createdDirs += dirPath
    }

    override fun copy(sourcePath: String, destPath: String) {
        copiedSources += sourcePath
    }

    override fun zip(sourceDirPath: String, destZipPath: String) {
        zipCallCount++
    }

    override fun deleteRecursive(dirPath: String) {
        deletedPaths += dirPath
    }

    // Return a fixed non-zero size so ExportResult.sizeBytes is non-negative.
    override fun size(filePath: String) = 1024L

    override fun joinPath(parent: String, child: String) = "$parent/$child"
}

/**
 * A [PlatformFileSystem] that throws on [zip] to test failure-path cleanup.
 */
private class FailOnZipFileSystem : PlatformFileSystem {
    var deleteRecursiveCalled = false

    override fun createTempDir(name: String) = "/tmp/$name"
    override fun createOutputPath(subdir: String, filename: String) = "/output/$subdir/$filename"
    override fun appPrivatePath(vararg segments: String) = "/private/${segments.joinToString("/")}"
    override fun writeText(filePath: String, text: String) = Unit
    override fun createDir(dirPath: String) = Unit
    override fun copy(sourcePath: String, destPath: String) = Unit
    override fun zip(sourceDirPath: String, destZipPath: String) {
        throw RuntimeException("Simulated zip failure")
    }
    override fun deleteRecursive(dirPath: String) {
        deleteRecursiveCalled = true
    }
    override fun size(filePath: String) = 0L
    override fun joinPath(parent: String, child: String) = "$parent/$child"
}
