package com.cooldog.triplens.export

import com.cooldog.triplens.i18n.Strings
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
 * All steps (including temp-dir creation) are inside a single try/catch. On any failure
 * the temp directory is deleted before the exception propagates, preventing orphaned files
 * from accumulating in cache storage. The zip file is also deleted on failure to prevent
 * a partial archive from being shared.
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
        private const val TAG = "TripLens/Export"
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
        exportLogI(TAG, "Starting export for groupId=$groupId")

        // --- Step 1: Load data ---
        exportLogI(TAG, "Step 1: Loading data")
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
        exportLogI(TAG, "Step 1 done: ${sessions.size} sessions, " +
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

        // tempDir and outputPath are declared before the try so the catch block can reference
        // them for cleanup. Both start as empty strings — the same "was it created?" sentinel
        // that outputPath already used before this refactor.
        var tempDir    = ""
        var outputPath = ""
        var sizeBytes  = 0L
        try {
            // --- Step 2: Create temp directory ---
            // Moved inside try (was previously outside) so any IO failure here is caught,
            // wrapped as ExportException, and propagated cleanly to the caller.
            exportLogI(TAG, "Step 2: Creating temp directory '$folderName'")
            tempDir = fileSystem.createTempDir(folderName)
            exportLogI(TAG, "Step 2 done: tempDir=$tempDir")

            // --- Step 3: Write index.json ---
            exportLogI(TAG, "Step 3: Writing index.json")
            val indexJson = IndexJsonBuilder.build(
                group            = group,
                sessions         = sessions,
                pointsBySession  = pointsBySession,
                notesBySession   = notesBySession,
                exportedAtMs     = nowMs
            )
            fileSystem.writeText(fileSystem.joinPath(tempDir, "index.json"), indexJson)
            exportLogI(TAG, "Step 3 done: index.json written (${indexJson.length} chars)")

            // --- Step 4: Write GPX files ---
            exportLogI(TAG, "Step 4: Writing GPX files")
            val tracksDir = fileSystem.joinPath(tempDir, "tracks")
            fileSystem.createDir(tracksDir)
            sessions.forEach { session ->
                val points = pointsBySession[session.id] ?: emptyList()
                val gpxContent = GpxWriter.write(session, points)
                val gpxPath = fileSystem.joinPath(tracksDir, "session_${session.id}.gpx")
                fileSystem.writeText(gpxPath, gpxContent)
                exportLogI(TAG, "Step 4: Wrote ${points.size} points for session ${session.id}")
            }
            exportLogI(TAG, "Step 4 done: ${sessions.size} GPX files written")

            // --- Step 5: Copy voice notes ---
            exportLogI(TAG, "Step 5: Copying voice notes")
            val notesDir = fileSystem.joinPath(tempDir, "notes")
            if (voiceNotes.isNotEmpty()) {
                fileSystem.createDir(notesDir)
                voiceNotes.forEach { note ->
                    val filename   = note.audioFilename!!
                    val sourcePath = fileSystem.appPrivatePath("notes", filename)
                    val destPath   = fileSystem.joinPath(notesDir, filename)
                    fileSystem.copy(sourcePath, destPath)
                    exportLogI(TAG, "Step 5: Copied $filename")
                }
            }
            exportLogI(TAG, "Step 5 done: ${voiceNotes.size} voice notes copied")

            // --- Step 6: Write README.txt ---
            // Strings.exportReadmeContent is always English — the desktop import tool must
            // be able to parse it regardless of the user's locale (see Strings.kt).
            exportLogI(TAG, "Step 6: Writing README.txt")
            fileSystem.writeText(fileSystem.joinPath(tempDir, "README.txt"), Strings.exportReadmeContent)
            exportLogI(TAG, "Step 6 done")

            // --- Step 7: Zip ---
            exportLogI(TAG, "Step 7: Zipping to '$archiveFilename'")
            outputPath = fileSystem.createOutputPath("exports", archiveFilename)
            fileSystem.zip(tempDir, outputPath)
            // Capture size once here so it can be reused in ExportResult without a second IO call.
            sizeBytes = fileSystem.size(outputPath)
            exportLogI(TAG, "Step 7 done: archive=$outputPath, size=$sizeBytes bytes")

        } catch (e: Exception) {
            // Clean up temp dir so cache doesn't accumulate partial exports.
            // tempDir is empty-string if createTempDir hadn't succeeded yet.
            exportLogE(TAG, "Export failed — cleaning up. tempDir='$tempDir' outputPath='$outputPath'", e)
            if (tempDir.isNotEmpty()) fileSystem.deleteRecursive(tempDir)
            // If the zip was partially written, delete it too to prevent a corrupt archive from
            // being shared. outputPath is empty-string if zip hadn't started yet.
            if (outputPath.isNotEmpty()) fileSystem.deleteRecursive(outputPath)
            throw ExportException("Export failed: ${e.message}", e)
        }

        // --- Step 8: Delete temp dir ---
        exportLogI(TAG, "Step 8: Deleting temp dir")
        fileSystem.deleteRecursive(tempDir)
        exportLogI(TAG, "Step 8 done")

        val result = ExportResult(path = outputPath, sizeBytes = sizeBytes)
        exportLogI(TAG, "Export complete: $result")
        return result
    }
}

/** Thrown when the export pipeline fails at any step. */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
