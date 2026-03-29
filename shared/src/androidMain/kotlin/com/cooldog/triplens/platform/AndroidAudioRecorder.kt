package com.cooldog.triplens.platform

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.util.UUID

private const val TAG = "TripLens/AudioRecorder"

/**
 * Android implementation of [AudioRecorder] using [android.media.MediaRecorder].
 *
 * ## Output format
 * Each recording is saved as M4A (MPEG-4 container, AAC-LC encoder) with the following settings
 * derived from TDD Section 7.2:
 * - Container:   [MediaRecorder.OutputFormat.MPEG_4] (produces a valid .m4a / .mp4 file)
 * - Encoder:     [MediaRecorder.AudioEncoder.AAC] (AAC-LC profile on all Android versions)
 * - Bit rate:    64 000 bps (sufficient for intelligible speech; keeps file sizes small)
 * - Channels:    1 (mono — voice notes do not benefit from stereo)
 *
 * ## Output path
 * Files are stored at `{context.filesDir}/notes/note_{uuid}.m4a` in app-private storage.
 * This directory is excluded from MediaStore scans (it is not on external storage), so voice
 * note files never appear in the system gallery. The export pipeline (Task 10) copies them
 * into the `.triplens` archive from this path.
 *
 * ## MediaRecorder constructor (API 26 vs 31+)
 * The no-arg [MediaRecorder()] constructor is deprecated since API 31. On API 31+ we use the
 * preferred [MediaRecorder(context)] constructor. On API 26–30, the no-arg constructor is the
 * only non-deprecated option. A [Build.VERSION.SDK_INT] check selects the right variant.
 *
 * ## Minimum recording duration
 * If [stop] is called immediately after [start] (within ~100 ms), the AAC encoder may not
 * have written any valid frames and [MediaRecorder.stop] will throw [RuntimeException].
 * The implementation catches this, deletes the partial file, and re-throws as
 * [IllegalStateException] so callers receive a consistent exception type.
 *
 * ## Thread safety
 * All three methods are expected to be called from the same thread (the UI thread via the
 * ViewModel). No synchronisation is applied. Concurrent calls from multiple threads would
 * produce unpredictable state; do not share an instance across threads.
 *
 * @param context Application context used to resolve [Context.getFilesDir] and to construct
 *                the [MediaRecorder] on API 31+. Must be the application context (not an
 *                activity) to avoid context leaks when the recorder outlives the activity.
 */
class AndroidAudioRecorder(private val context: Context) : AudioRecorder {

    // Simple boolean flag rather than a sealed class — the recorder has only two meaningful
    // states: idle (not recording) and recording. Using a flag avoids the overhead of
    // exhaustive when-branches for a two-state machine.
    private var isRecording = false

    // Non-null only while isRecording == true.
    private var recorder: MediaRecorder? = null

    // The file being written. Non-null only while isRecording == true.
    private var outputFile: File? = null

    override fun start() {
        // Guard: prevent double-start. The caller (RecordingViewModel) should never reach
        // this, but a clear error message is better than a cryptic MediaRecorder exception.
        check(!isRecording) {
            "Cannot start: a recording is already in progress. Call stop() or cancel() first."
        }

        // Ensure the notes directory exists. mkdirs() is idempotent — safe to call every time.
        val notesDir = File(context.filesDir, "notes")
        notesDir.mkdirs()

        val file = File(notesDir, "note_${UUID.randomUUID()}.m4a")
        outputFile = file

        // Prefer the context-aware constructor on API 31+ to avoid the deprecation warning.
        // The no-arg constructor is still functional on API 26–30.
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mr.apply {
                // MIC: device's primary microphone. VOICE_RECOGNITION and CAMCORDER are
                // alternatives but MIC gives the broadest compatibility on emulators.
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // MPEG_4 container produces valid M4A files. THREE_GPP would also work but
                // is less universally supported by desktop players.
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                // AAC-LC encoder: widely supported, good quality-to-size ratio for speech.
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                // Mono: voice notes do not benefit from stereo and stereo doubles file size.
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // If prepare() or start() fails (e.g. RECORD_AUDIO not granted, no microphone),
            // release the MediaRecorder and delete the empty stub file to keep the FS clean.
            Log.e(TAG, "start: MediaRecorder.prepare()/start() failed — releasing and cleaning up", e)
            mr.release()
            file.delete()
            outputFile = null
            throw e  // Re-throw so the ViewModel can surface an error to the UI.
        }

        recorder = mr
        isRecording = true
        Log.i(TAG, "start: recording to ${file.absolutePath}")
    }

    override fun stop(): String {
        check(isRecording) {
            "Cannot stop: no recording is in progress. Call start() first."
        }

        // outputFile is always non-null when isRecording is true — the invariant is maintained
        // by start() setting both fields atomically before setting isRecording = true.
        val file = requireNotNull(outputFile) {
            "outputFile is null while isRecording is true — this is a bug in AndroidAudioRecorder"
        }

        try {
            recorder!!.apply {
                stop()   // Flushes the encoder and finalises the container.
                release()
            }
        } catch (e: Exception) {
            // MediaRecorder.stop() throws RuntimeException when the recording is too short
            // to produce any valid frames (typically < 100 ms). Delete the partial file
            // so no zero-byte or corrupt .m4a files accumulate in the notes directory.
            Log.e(TAG, "stop: MediaRecorder.stop() threw — deleting partial file ${file.absolutePath}", e)
            file.delete()
            recorder = null
            outputFile = null
            isRecording = false
            throw IllegalStateException(
                "Recording failed: the audio output could not be finalised. " +
                "The recording may have been too short. Cause: ${e.message}", e
            )
        }

        recorder = null
        outputFile = null
        isRecording = false

        Log.i(TAG, "stop: saved ${file.absolutePath} (${file.length()} bytes)")
        return file.absolutePath
    }

    override fun cancel() {
        if (!isRecording) {
            // No-op: nothing to cancel. Callers (e.g. onCleared() in RecordingViewModel)
            // should be able to call cancel() defensively without checking state first.
            Log.d(TAG, "cancel: recorder is already idle — no-op")
            return
        }

        val file = outputFile

        try {
            recorder?.apply {
                stop()   // May throw RuntimeException if recording was too short — handled below.
                release()
            }
        } catch (e: Exception) {
            // A too-short recording (< ~100 ms) causes stop() to throw. This is expected
            // when the user taps cancel immediately after start. The file is deleted regardless.
            Log.w(TAG, "cancel: MediaRecorder.stop() threw (recording may have been too short) " +
                    "— partial file will still be deleted", e)
        } finally {
            // Always release native resources, even if stop() threw.
            recorder?.release()
            recorder = null
        }

        // Delete the partial file. Log the result but do not throw on failure — the contract
        // of cancel() is to attempt cleanup, not to guarantee it.
        if (file != null) {
            val deleted = file.delete()
            Log.i(TAG, "cancel: deleted partial file ${file.absolutePath} (success=$deleted)")
        }
        outputFile = null
        isRecording = false
    }
}
