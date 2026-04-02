package com.cooldog.triplens.ui.recording

import com.cooldog.triplens.model.MediaReference
import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.platform.AudioRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Bundles all external dependencies injected into [RecordingViewModel].
 *
 * ## Why a data class instead of flat constructor parameters?
 * By the end of Task 13 the ViewModel requires 13+ lambdas. Several share identical
 * functional types (e.g. `(String, Long) -> Unit`) and would be indistinguishable by
 * position alone, making swaps impossible to catch at the call site. Named fields in a
 * data class make every binding explicit and self-documenting.
 *
 * ## Pattern
 * Each lambda wraps exactly one repository method or platform call. The Koin
 * [com.cooldog.triplens.di.androidModule] wires the real calls; unit tests supply fakes.
 */
data class RecordingDeps(

    // ── Idle → Active transition ──────────────────────────────────────────────────

    val createGroupFn: (id: String, name: String, now: Long) -> Unit,
    val createSessionFn: (id: String, groupId: String, name: String, startTime: Long) -> Unit,
    /** Must use ContextCompat.startForegroundService on API 26+. */
    val startService: (sessionId: String, profile: AccuracyProfile) -> Unit,

    // ── Active state — data fetching (polled every 5 s) ──────────────────────────

    val getTrackPointsFn: (sessionId: String) -> List<TrackPoint>,
    val getMediaRefsFn:   (sessionId: String) -> List<MediaReference>,
    val getNotesFn:       (sessionId: String) -> List<Note>,

    // ── Active state — writes ────────────────────────────────────────────────────

    /** lat/lng come from the most recent non-paused TrackPoint, or 0.0/0.0 if none yet. */
    val createTextNoteFn: (
        id: String, sessionId: String, content: String,
        createdAt: Long, lat: Double, lng: Double,
    ) -> Unit,

    /**
     * [audioFilename] is the bare filename (not the full path) as required by
     * [com.cooldog.triplens.repository.NoteRepository.createVoiceNote].
     */
    val createVoiceNoteFn: (
        id: String, sessionId: String, audioFilename: String,
        durationSeconds: Int, createdAt: Long, lat: Double, lng: Double,
    ) -> Unit,

    /** setEndTime SQL also sets status = 'completed'. No separate updateStatus call needed. */
    val completeSessionFn: (id: String, endTime: Long) -> Unit,

    /** Sends ACTION_STOP intent to LocationTrackingService. */
    val stopServiceFn: () -> Unit,

    // ── Platform ─────────────────────────────────────────────────────────────────

    /**
     * Stateful voice recorder. Registered as Koin `factory` so each ViewModel gets a
     * fresh idle instance. [RecordingViewModel.onCleared] always calls [AudioRecorder.cancel]
     * defensively regardless of current recording state.
     */
    val audioRecorder: AudioRecorder,

    // ── Testability hooks ────────────────────────────────────────────────────────

    /** Injected so tests pass `{ FIXED_EPOCH }` for deterministic date strings and durations. */
    val clock: () -> Long = { System.currentTimeMillis() },

    /** Injected so tests pass a [kotlinx.coroutines.test.StandardTestDispatcher]. */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
