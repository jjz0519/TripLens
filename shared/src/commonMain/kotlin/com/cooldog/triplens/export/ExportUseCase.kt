package com.cooldog.triplens.export

import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TrackPointRepository
import com.cooldog.triplens.repository.TripRepository

/**
 * Orchestrates the 8-step export pipeline that produces a `.triplens` archive.
 *
 * ## Archive structure
 * ```
 * triplens-{name}-{date}/
 * ├── index.json                 Full trip data (groups, sessions, tracks, notes)
 * ├── tracks/
 * │   └── session_{id}.gpx      GPX 1.1 per session with triplens: extensions
 * ├── notes/
 * │   └── note_{uuid}.m4a       Voice note audio files (photos/videos excluded — too large)
 * └── README.txt
 * ```
 *
 * ## Steps
 * 1. Load TripGroup and all related data from the database.
 * 2. Create a named temp directory in platform cache storage.
 * 3. Write `index.json` using [IndexJsonBuilder].
 * 4. Create `tracks/` subdirectory; write one GPX per session using [GpxWriter].
 * 5. Create `notes/` subdirectory; copy voice note `.m4a` files from app private storage.
 * 6. Write `README.txt`.
 * 7. Zip the temp directory to permanent output storage; the archive name is derived from
 *    the group name and its creation date.
 * 8. Delete the temp directory.
 *
 * ## Error handling
 * If any step throws, the temp directory is deleted before the exception propagates so
 * no orphaned files accumulate in cache storage. The zip file is also deleted on failure
 * to prevent a partial archive from being shared.
 *
 * @param tripRepo       Loads the [TripGroup].
 * @param sessionRepo    Loads sessions for the group.
 * @param trackPointRepo Loads track points per session.
 * @param noteRepo       Loads notes per session; voice note filenames point to `.m4a` files.
 * @param mediaRefRepo   Not used for export (photos/videos excluded), included for completeness.
 * @param fileSystem     Platform file operations (temp dir, zip, copy, etc.).
 */
class ExportUseCase(
    private val tripRepo:       TripRepository,
    private val sessionRepo:    SessionRepository,
    private val trackPointRepo: TrackPointRepository,
    private val noteRepo:       NoteRepository,
    @Suppress("UnusedPrivateProperty")
    private val mediaRefRepo:   MediaRefRepository,
    private val fileSystem:     PlatformFileSystem
) {

    companion object {
        private const val LOG_TAG = "[TripLens/Export]"
        const val README_CONTENT = """TripLens Export — schema_version: 1
=====================================

Contents:
  index.json              Full trip data (groups, sessions, track points, notes)
  tracks/session_*.gpx    GPX 1.1 track files per session, with transport mode extensions
  notes/note_*.m4a        Voice note audio recordings

To import into TripLens Desktop, open this .triplens file from File → Import.

Format spec: https://github.com/triplens/triplens/blob/main/docs/TripLens-TDD.md
"""
    }

    /**
     * Exports the [TripGroup] identified by [groupId] to a `.triplens` archive.
     *
     * @param groupId    The ID of the TripGroup to export.
     * @param nowMs      Current time as epoch millis UTC; written to `exported_at` in index.json.
     * @return           [ExportResult] containing the archive path and file size.
     * @throws IllegalArgumentException if no group with [groupId] exists.
     * @throws ExportException          if any pipeline step fails (temp dir is cleaned up first).
     */
    suspend fun export(groupId: String, nowMs: Long): ExportResult {
        println("$LOG_TAG Starting export for groupId=$groupId")

        // --- Step 1: Load data ---
        println("$LOG_TAG Step 1: Loading data")
        val group = tripRepo.getGroupById(groupId)
            ?: throw IllegalArgumentException("No TripGroup found for id=$groupId")
        val sessions    = sessionRepo.getSessionsByGroup(groupId)
        val pointsBySession = sessions.associate { s ->
            s.id to trackPointRepo.getBySession(s.id)
        }
        val notesBySession  = sessions.associate { s ->
            s.id to noteRepo.getBySession(s.id)
        }
        val voiceNotes = notesBySession.values.flatten().filter { it.audioFilename != null }
        println("$LOG_TAG Step 1 done: ${sessions.size} sessions, " +
                "${pointsBySession.values.sumOf { it.size }} points, " +
                "${voiceNotes.size} voice notes")

        // Derive archive name from group name and creation date (first 10 chars of ISO 8601 = YYYY-MM-DD).
        val sanitizedName = group.name
            .replace(Regex("[^a-zA-Z0-9_-]"), "-")
            .take(30)
            .trimEnd('-')
        val dateStr = GpxWriter.formatIso8601(group.createdAt).substring(0, 10)
        val folderName = "triplens-$sanitizedName-$dateStr"
        val archiveFilename = "$folderName.triplens"

        // --- Step 2: Create temp directory ---
        println("$LOG_TAG Step 2: Creating temp directory '$folderName'")
        val tempDir = fileSystem.createTempDir(folderName)
        println("$LOG_TAG Step 2 done: tempDir=$tempDir")

        var outputPath = ""
        try {
            // --- Step 3: Write index.json ---
            println("$LOG_TAG Step 3: Writing index.json")
            val indexJson = IndexJsonBuilder.build(
                group            = group,
                sessions         = sessions,
                pointsBySession  = pointsBySession,
                notesBySession   = notesBySession,
                exportedAtMs     = nowMs
            )
            fileSystem.writeText(fileSystem.joinPath(tempDir, "index.json"), indexJson)
            println("$LOG_TAG Step 3 done: index.json written (${indexJson.length} chars)")

            // --- Step 4: Write GPX files ---
            println("$LOG_TAG Step 4: Writing GPX files")
            val tracksDir = fileSystem.joinPath(tempDir, "tracks")
            fileSystem.createDir(tracksDir)
            sessions.forEach { session ->
                val points = pointsBySession[session.id] ?: emptyList()
                val gpxContent = GpxWriter.write(session, points)
                val gpxPath = fileSystem.joinPath(tracksDir, "session_${session.id}.gpx")
                fileSystem.writeText(gpxPath, gpxContent)
                println("$LOG_TAG Step 4: Wrote ${points.size} points for session ${session.id}")
            }
            println("$LOG_TAG Step 4 done: ${sessions.size} GPX files written")

            // --- Step 5: Copy voice notes ---
            println("$LOG_TAG Step 5: Copying voice notes")
            val notesDir = fileSystem.joinPath(tempDir, "notes")
            if (voiceNotes.isNotEmpty()) {
                fileSystem.createDir(notesDir)
                voiceNotes.forEach { note ->
                    val filename   = note.audioFilename!!
                    val sourcePath = fileSystem.appPrivatePath("notes", filename)
                    val destPath   = fileSystem.joinPath(notesDir, filename)
                    fileSystem.copy(sourcePath, destPath)
                    println("$LOG_TAG Step 5: Copied $filename")
                }
            }
            println("$LOG_TAG Step 5 done: ${voiceNotes.size} voice notes copied")

            // --- Step 6: Write README.txt ---
            println("$LOG_TAG Step 6: Writing README.txt")
            fileSystem.writeText(fileSystem.joinPath(tempDir, "README.txt"), README_CONTENT)
            println("$LOG_TAG Step 6 done")

            // --- Step 7: Zip ---
            println("$LOG_TAG Step 7: Zipping to '$archiveFilename'")
            outputPath = fileSystem.createOutputPath("exports", archiveFilename)
            fileSystem.zip(tempDir, outputPath)
            val sizeBytes = fileSystem.size(outputPath)
            println("$LOG_TAG Step 7 done: archive=$outputPath, size=$sizeBytes bytes")

        } catch (e: Exception) {
            // Clean up temp dir so cache doesn't accumulate partial exports.
            println("$LOG_TAG Export failed at a pipeline step — cleaning up temp dir: ${e.message}")
            fileSystem.deleteRecursive(tempDir)
            // If the zip was partially written, delete it too to prevent a corrupt archive from
            // being shared. outputPath is empty-string if zip hadn't started yet.
            if (outputPath.isNotEmpty()) {
                fileSystem.deleteRecursive(outputPath)
            }
            throw ExportException("Export failed: ${e.message}", e)
        }

        // --- Step 8: Delete temp dir ---
        println("$LOG_TAG Step 8: Deleting temp dir")
        fileSystem.deleteRecursive(tempDir)
        println("$LOG_TAG Step 8 done")

        val result = ExportResult(path = outputPath, sizeBytes = fileSystem.size(outputPath))
        println("$LOG_TAG Export complete: $result")
        return result
    }
}

/** Thrown when the export pipeline fails at any step. */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
