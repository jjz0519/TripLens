package com.cooldog.triplens.platform

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "TripLens/AudioRecorderTest"

/**
 * Instrumented tests for [AndroidAudioRecorder].
 *
 * ## Why instrumented (not unit)
 * [android.media.MediaRecorder] is a framework class backed by native audio pipeline code.
 * It cannot be exercised in a JVM-only unit test — a real or emulated microphone and audio
 * HAL are required for [MediaRecorder.start] and [MediaRecorder.stop] to succeed and produce
 * a valid file. All tests in this class must run on a device or emulator.
 *
 * ## RECORD_AUDIO permission
 * [GrantPermissionRule] grants the permission at the shell level before any test runs.
 * The test runner process stays in the foreground throughout, which satisfies Android 14+
 * microphone-while-foreground requirements without needing a separate ActivityScenarioRule.
 *
 * ## File cleanup
 * Files created by successful [AndroidAudioRecorder.stop] calls are tracked in [createdFiles]
 * and deleted in [tearDown]. Cancelled recordings delete their own files; [tearDown] also
 * calls [cancel][AndroidAudioRecorder.cancel] as a safety net for any test that fails mid-recording.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecorderTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO
    )

    private lateinit var context: Context
    private lateinit var recorder: AndroidAudioRecorder

    // Tracks files returned by stop() so they can be deleted in @After.
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        recorder = AndroidAudioRecorder(context)
    }

    @After
    fun tearDown() {
        // Safety net: cancel any still-active recording (no-op if already stopped/cancelled).
        try {
            recorder.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "tearDown: cancel() threw — recorder may already be idle", e)
        }

        // Delete any files created by stop() that were not cleaned up by the test itself.
        createdFiles.forEach { file ->
            if (file.exists()) {
                Log.w(TAG, "tearDown: deleting leftover test file: ${file.absolutePath}")
                file.delete()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 1 — basic record + stop produces a non-empty file
    // -------------------------------------------------------------------------

    /**
     * A 2-second recording produces a file at the path returned by [stop], the file
     * exists on disk, and its size is greater than zero.
     */
    @Test
    fun recordAndStop_fileExistsWithContent() {
        recorder.start()
        Thread.sleep(2_000)
        val path = recorder.stop()

        val file = File(path)
        createdFiles.add(file)

        assertTrue("Output file should exist after stop()", file.exists())
        assertTrue("Output file should have content (size > 0)", file.length() > 0)
    }

    // -------------------------------------------------------------------------
    // Test 2 — cancel() deletes the partial file, leaving no orphans
    // -------------------------------------------------------------------------

    /**
     * After [cancel], no new .m4a files should remain in the notes directory.
     * The recorder creates the output file before recording starts; cancel() must delete it.
     *
     * Snapshot strategy: compare the set of .m4a files in the notes dir before [start] and
     * after [cancel] so the test is robust even if pre-existing files are already present.
     */
    @Test
    fun cancelAfterStart_fileIsDeleted() {
        val notesDir = File(context.filesDir, "notes").also { it.mkdirs() }
        val filesBefore = notesDir.listFiles { _, name -> name.endsWith(".m4a") }?.toSet()
            ?: emptySet()

        recorder.start()
        Thread.sleep(1_000)
        recorder.cancel()

        val filesAfter = notesDir.listFiles { _, name -> name.endsWith(".m4a") }?.toSet()
            ?: emptySet()
        val addedFiles = filesAfter - filesBefore

        assertTrue(
            "cancel() must leave no orphan .m4a files; found: $addedFiles",
            addedFiles.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Test 3 — stop() after cancel() throws (recorder is back to idle)
    // -------------------------------------------------------------------------

    /**
     * [cancel] resets the recorder to idle state. A subsequent [stop] call must throw
     * [IllegalStateException] because there is no active recording to stop.
     * No orphan files should exist after either call.
     */
    @Test
    fun stopAfterCancel_throwsIllegalStateException() {
        recorder.start()
        Thread.sleep(1_000)
        recorder.cancel()

        try {
            recorder.stop()
            fail("stop() after cancel() should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            // Expected — verify the message is non-empty so the caller can diagnose the problem.
            assertTrue(
                "IllegalStateException message should be descriptive, was: '${e.message}'",
                e.message?.isNotBlank() == true
            )
        }

        // Verify no files were created as a side-effect of the failed stop() call.
        val notesDir = File(context.filesDir, "notes")
        val m4aFiles = notesDir.listFiles { _, name -> name.endsWith(".m4a") } ?: emptyArray()
        assertTrue(
            "No orphan files should remain after cancel() + failed stop(). Found: ${m4aFiles.map { it.name }}",
            m4aFiles.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Test 4 — stop() without start() throws IllegalStateException
    // -------------------------------------------------------------------------

    /**
     * Calling [stop] on a freshly constructed [AndroidAudioRecorder] (never started) must throw
     * [IllegalStateException] with a descriptive message explaining why the call is invalid.
     */
    @Test
    fun stopWithoutStart_throwsIllegalStateException() {
        try {
            recorder.stop()
            fail("stop() without start() should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "IllegalStateException message should be descriptive, was: '${e.message}'",
                e.message?.isNotBlank() == true
            )
        }
    }

    // -------------------------------------------------------------------------
    // Test 5 — output is a valid M4A container (ftyp box)
    // -------------------------------------------------------------------------

    /**
     * The file produced by [stop] must be a valid ISO Base Media File (MP4/M4A) container.
     *
     * ## Verification strategy
     * All valid MPEG-4 / M4A files start with an `ftyp` (file-type) box. The binary layout is:
     *   - Bytes 0–3:  box size as a big-endian uint32 (typically 20 or 24)
     *   - Bytes 4–7:  box type as ASCII: `f`, `t`, `y`, `p` (0x66 0x74 0x79 0x70)
     *
     * Reading bytes 4–7 and comparing them to "ftyp" is sufficient to confirm the file is a
     * recognised container rather than a truncated or corrupt audio stream.
     */
    @Test
    fun outputIsValidM4AContainer() {
        recorder.start()
        Thread.sleep(1_000)
        val path = recorder.stop()

        val file = File(path)
        createdFiles.add(file)

        assertTrue("Output file must exist to verify container format", file.exists())
        assertTrue("Output file must have at least 8 bytes to read the ftyp box header",
            file.length() >= 8)

        val header = ByteArray(8)
        file.inputStream().use { stream ->
            val bytesRead = stream.read(header)
            assertEquals("Could not read 8 bytes from output file", 8, bytesRead)
        }

        // Bytes 4–7 are the box type. For M4A produced by MediaRecorder MPEG_4, this is "ftyp".
        val boxType = String(header, 4, 4, Charsets.US_ASCII)
        assertEquals(
            "File must begin with an ftyp box (valid M4A/MP4 container). Got: '$boxType'",
            "ftyp",
            boxType
        )
    }

    // -------------------------------------------------------------------------
    // Test 6 — output path is under filesDir/notes/ and has .m4a extension
    // -------------------------------------------------------------------------

    /**
     * Voice note files must be stored in app-private storage under `{filesDir}/notes/` so they
     * are not accessible to other apps and are removed when the app is uninstalled. The `.m4a`
     * extension is required by the export pipeline (Task 10) and the session review player.
     */
    @Test
    fun outputPath_isInPrivateNotesDirectory() {
        recorder.start()
        Thread.sleep(1_000)
        val path = recorder.stop()

        val file = File(path)
        createdFiles.add(file)

        val expectedNotesDir = File(context.filesDir, "notes").canonicalPath
        assertTrue(
            "Output file should be under filesDir/notes/. Got: $path",
            file.canonicalPath.startsWith(expectedNotesDir)
        )
        assertTrue(
            "Output file should end with .m4a. Got: $path",
            path.endsWith(".m4a")
        )
        assertFalse(
            "notes/ directory must be inside app-private filesDir (not external storage). Got: $path",
            file.canonicalPath.contains("sdcard") || file.canonicalPath.contains("external")
        )
    }
}
