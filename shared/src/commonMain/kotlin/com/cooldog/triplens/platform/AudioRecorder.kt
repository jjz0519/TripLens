package com.cooldog.triplens.platform

/**
 * Platform interface for recording voice notes.
 *
 * The Android implementation ([AndroidAudioRecorder] in androidMain) uses
 * [android.media.MediaRecorder] to produce M4A (AAC-LC, 64 kbps, mono) files stored
 * in app-private storage under `{filesDir}/notes/`. A future iOS implementation would
 * use AVAudioRecorder with the same codec settings.
 *
 * ## State machine
 * An instance starts in the **idle** state. The allowed transitions are:
 *
 * ```
 * idle   ──start()──▶  recording  ──stop()───▶  idle   (returns file path)
 *                    │                         │
 *                    └──cancel()──▶  idle      │
 *                                              │
 * idle   ──stop()──▶  IllegalStateException   │
 * idle   ──cancel()──▶  no-op                 │
 * ```
 *
 * After [stop] or [cancel], the instance returns to idle and can be [start]ed again.
 * This allows a single [AudioRecorder] instance to record multiple voice notes in sequence
 * within a session, without needing to create a new instance each time.
 *
 * ## Lifecycle
 * Create one instance per session (injected via Koin in Task 9). The instance is stateful
 * (it tracks the current recording file path and [MediaRecorder] reference), so it must
 * not be shared across concurrent coroutines. All three methods are called from the UI
 * thread (via the ViewModel) and block only briefly; no coroutine dispatch is required.
 *
 * ## File management
 * [stop] returns the absolute path of the completed file. The caller (ViewModel / use-case
 * layer) is responsible for registering the file as a [com.cooldog.triplens.model.Note]
 * via [com.cooldog.triplens.repository.NoteRepository.createVoiceNote].
 *
 * [cancel] deletes the partial file. If [stop] itself throws (e.g. the recording was too
 * short for the encoder to produce valid output), the implementation also deletes the
 * partial file before re-throwing so no orphan files are left on disk.
 */
interface AudioRecorder {

    /**
     * Starts a new recording session. Creates the output file and arms the audio pipeline.
     *
     * @throws IllegalStateException if a recording is already in progress.
     * @throws Exception (platform-specific) if the audio source cannot be opened, e.g. if
     *                   [android.Manifest.permission.RECORD_AUDIO] is not granted.
     */
    fun start()

    /**
     * Stops the current recording, flushes the encoder, and closes the output file.
     *
     * @return Absolute path of the finished M4A file. The caller must persist this path
     *         via [com.cooldog.triplens.repository.NoteRepository.createVoiceNote].
     * @throws IllegalStateException if no recording is currently in progress (i.e. [start]
     *                               was never called, or [cancel] was called first).
     * @throws Exception (platform-specific) if the encoder produces an invalid output
     *                   (e.g. recording was too short). In this case the partial file is
     *                   deleted before the exception is propagated.
     */
    fun stop(): String

    /**
     * Cancels the current recording and deletes the partial output file.
     *
     * This is a no-op if no recording is in progress. It never throws.
     *
     * After this call the instance is back in the idle state and [start] may be called again.
     */
    fun cancel()
}
