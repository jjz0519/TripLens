package com.cooldog.triplens.ui.recording

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.model.MediaReference
import com.cooldog.triplens.model.MediaType
import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * ViewModel for the Recording screen — drives the full state machine from idle to active session.
 *
 * ## State machine
 * ```
 * Idle ──onStartTapped(granted=true)──▶ StartingSession ──(IO complete)──▶ ActiveRecording
 *  ▲                                                                              │
 *  └──────────────────────── onStopConfirmed() ───────────────────────────────────┘
 * ```
 *
 * ## Design: RecordingDeps instead of flat parameters
 * All external calls are bundled in [RecordingDeps]. This prevents accidental lambda swap bugs
 * (several lambdas share identical functional types) and makes the Koin binding and test helpers
 * readable via named arguments.
 *
 * ## Polling strategy
 * No Flow-based reactive queries — repositories expose one-shot reads. The active state is
 * refreshed every [POLL_INTERVAL_MS] (5 s) to match the spec's "polyline updates throttled to
 * every 5 seconds" requirement. An immediate poll also runs on session start and after each
 * note write so the media strip updates without waiting 5 s.
 *
 * ## Timer
 * [UiState.ActiveRecording.elapsedSeconds] is incremented every second by the timer coroutine.
 * Incrementing by 1 rather than computing from wall clock makes the unit test predictable:
 * `advanceTimeBy(3_000)` always yields `elapsedSeconds == 3`.
 *
 * ## Thread safety
 * All [UiState.ActiveRecording] mutations use [MutableStateFlow.update] so read-modify-write
 * is atomic even when the timer and poll coroutines run concurrently.
 */
class RecordingViewModel(private val deps: RecordingDeps) : ViewModel() {

    // ── UiState ─────────────────────────────────────────────────────────────────

    sealed interface UiState {
        /** "Start Recording" button visible and enabled. */
        data object Idle : UiState

        /** Creating TripGroup + Session + starting service. Button shows loading indicator. */
        data object StartingSession : UiState

        /**
         * A session is running. All display data is polled and stored here.
         *
         * @param sessionId         ID of the active session.
         * @param groupName         TripGroup name shown in the top bar (e.g. "2026-04-02").
         * @param elapsedSeconds    Incremented every second by the timer coroutine.
         * @param trackPoints       Last polled result from [RecordingDeps.getTrackPointsFn].
         * @param recentMedia       Merged, sorted, 10-capped list from [MediaItem].
         * @param isVoiceRecording  True while [RecordingDeps.audioRecorder] is running.
         * @param voiceElapsedSeconds Incremented every second while voice recording is active.
         * @param isCameraFollowing True until the user pans the map away; re-center button shown otherwise.
         */
        data class ActiveRecording(
            val sessionId: String,
            val groupName: String,
            val elapsedSeconds: Long = 0L,
            val trackPoints: List<TrackPoint> = emptyList(),
            val recentMedia: List<MediaItem> = emptyList(),
            val isVoiceRecording: Boolean = false,
            val voiceElapsedSeconds: Long = 0L,
            val isCameraFollowing: Boolean = true,
        ) : UiState
    }

    // ── Events ───────────────────────────────────────────────────────────────────

    sealed interface Event {
        /**
         * Session created and service started. RecordingScreen calls
         * [com.cooldog.triplens.ui.AppViewModel.onSessionActiveChanged] in response.
         */
        data object NavigateToActiveRecording : Event

        /** Location permission revoked post-onboarding. Show [PermissionRationaleDialog]. */
        data object ShowPermissionRationale : Event

        /** User tapped Stop and confirmed the dialog. Navigate away from the recording screen. */
        data class NavigateToSessionReview(val sessionId: String) : Event

        /**
         * User tapped the Stop button. RecordingScreen shows the confirmation dialog.
         * The session is NOT ended until [onStopConfirmed] is called.
         */
        data object ShowStopConfirmation : Event
    }

    // ── State and event plumbing ─────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // ── Coroutine job handles ────────────────────────────────────────────────────

    private var timerJob: Job? = null
    private var pollJob: Job? = null
    private var voiceTimerJob: Job? = null

    // ── Public API — idle state ──────────────────────────────────────────────────

    /**
     * Called when the user taps "Start Recording".
     *
     * If [locationGranted] is false, emits [Event.ShowPermissionRationale] and returns.
     * If a session is already starting or active, this is a no-op (double-tap guard).
     *
     * On success: auto-creates a [com.cooldog.triplens.model.TripGroup] named today's UTC date
     * and a [com.cooldog.triplens.model.Session] named "Session 1", starts
     * [com.cooldog.triplens.service.LocationTrackingService], then transitions to
     * [UiState.ActiveRecording].
     */
    fun onStartTapped(locationGranted: Boolean) {
        if (!locationGranted) {
            Log.w(TAG, "onStartTapped: location not granted — emitting ShowPermissionRationale")
            viewModelScope.launch { _events.send(Event.ShowPermissionRationale) }
            return
        }
        if (_uiState.value != UiState.Idle) {
            Log.d(TAG, "onStartTapped: ignored — state is ${_uiState.value}")
            return
        }
        _uiState.value = UiState.StartingSession
        Log.i(TAG, "onStartTapped: auto-creating TripGroup + Session")

        viewModelScope.launch {
            withContext(deps.ioDispatcher) {
                val now       = deps.clock()
                val groupId   = UUID.randomUUID().toString()
                val sessionId = UUID.randomUUID().toString()

                // Locale.US ensures consistent yyyy-MM-dd output regardless of device locale.
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(now))

                deps.createGroupFn(groupId, dateStr, now)
                Log.d(TAG, "Created TripGroup id=$groupId name=$dateStr")

                deps.createSessionFn(sessionId, groupId, "Session 1", now)
                Log.d(TAG, "Created Session id=$sessionId groupId=$groupId")

                deps.startService(sessionId, AccuracyProfile.STANDARD, now)
                Log.i(TAG, "LocationTrackingService started sessionId=$sessionId profile=STANDARD")

                // Transition to active state before returning to the main dispatcher.
                _uiState.value = UiState.ActiveRecording(
                    sessionId = sessionId,
                    groupName = dateStr,
                )
            }

            val sessionId = (_uiState.value as? UiState.ActiveRecording)?.sessionId ?: return@launch

            // Notify the screen so AppViewModel can start the bottom-nav pulse animation.
            _events.send(Event.NavigateToActiveRecording)

            // Start background loops that maintain the active state display data.
            launchTimerLoop()
            launchPollLoop(sessionId)
            Log.i(TAG, "Active recording loops started sessionId=$sessionId")
        }
    }

    // ── Public API — active state ────────────────────────────────────────────────

    /**
     * User tapped the Stop button. Emits [Event.ShowStopConfirmation] so the screen
     * can display a confirmation dialog before committing to end the session.
     * No-op if the session is not currently active.
     */
    fun onStopTapped() {
        if (_uiState.value !is UiState.ActiveRecording) {
            Log.d(TAG, "onStopTapped: ignored — not in ActiveRecording state")
            return
        }
        viewModelScope.launch { _events.send(Event.ShowStopConfirmation) }
    }

    /**
     * User confirmed the stop dialog. Completes the session in the DB, stops the service,
     * cancels all background loops, and emits [Event.NavigateToSessionReview].
     *
     * If voice recording was active, [AudioRecorder.cancel] is called to discard the
     * partial file before the session ends.
     */
    fun onStopConfirmed() {
        val state = _uiState.value as? UiState.ActiveRecording ?: run {
            Log.d(TAG, "onStopConfirmed: ignored — not in ActiveRecording state")
            return
        }
        val sessionId = state.sessionId

        cancelActiveLoops()

        // Cancel any in-progress voice recording before ending the session.
        if (state.isVoiceRecording) {
            deps.audioRecorder.cancel()
            Log.w(TAG, "Voice recording cancelled on session stop sessionId=$sessionId")
        }

        Log.i(TAG, "onStopConfirmed: ending session sessionId=$sessionId")

        viewModelScope.launch {
            withContext(deps.ioDispatcher) {
                deps.completeSessionFn(sessionId, deps.clock())
                deps.stopServiceFn()
            }
            // Reset to Idle so the screen renders a clean state while navigation is in flight.
            _uiState.value = UiState.Idle
            Log.i(TAG, "Session completed and service stopped sessionId=$sessionId")
            _events.send(Event.NavigateToSessionReview(sessionId))
        }
    }

    /**
     * Saves a text note attached to the current session. Uses the most recent non-paused
     * [TrackPoint] for GPS coordinates; falls back to (0.0, 0.0) if no fix has been
     * received yet (note will display without a map pin until trajectory matching runs).
     */
    fun onSaveTextNote(content: String) {
        val state = _uiState.value as? UiState.ActiveRecording ?: return
        val sessionId = state.sessionId
        val lastPoint = state.trackPoints.lastOrNull { !it.isAutoPaused }
        val lat = lastPoint?.latitude  ?: 0.0
        val lng = lastPoint?.longitude ?: 0.0
        Log.d(TAG, "onSaveTextNote: sessionId=$sessionId lat=$lat lng=$lng")

        viewModelScope.launch {
            withContext(deps.ioDispatcher) {
                deps.createTextNoteFn(
                    UUID.randomUUID().toString(), sessionId, content, deps.clock(), lat, lng,
                )
            }
            // Kick an immediate refresh so the note appears in the media strip without waiting
            // for the next scheduled 5-second poll.
            val s = _uiState.value as? UiState.ActiveRecording ?: return@launch
            refreshActiveData(s.sessionId)
        }
    }

    /**
     * Starts a voice note recording. Sets [UiState.ActiveRecording.isVoiceRecording] to true
     * and starts the voice elapsed-time ticker. No-op if already recording.
     */
    fun onVoiceNoteStart() {
        val state = _uiState.value as? UiState.ActiveRecording ?: return
        if (state.isVoiceRecording) {
            Log.d(TAG, "onVoiceNoteStart: ignored — already recording")
            return
        }
        Log.i(TAG, "onVoiceNoteStart: starting voice recording sessionId=${state.sessionId}")

        viewModelScope.launch {
            withContext(deps.ioDispatcher) { deps.audioRecorder.start() }
            updateActiveState { it.copy(isVoiceRecording = true, voiceElapsedSeconds = 0L) }
            launchVoiceTimerLoop()
        }
    }

    /**
     * Stops the current voice recording, persists the note, and kicks an immediate data refresh.
     * Duration is derived from [UiState.ActiveRecording.voiceElapsedSeconds] so it matches the
     * elapsed display. Clamps to 1 second minimum in case the recorder produced a valid file
     * for a very short recording.
     *
     * If [AudioRecorder.stop] throws (e.g. recording too short for the AAC encoder), the partial
     * file is deleted by the implementation and the voice state is cleared without crashing.
     */
    fun onVoiceNoteStop() {
        val state = _uiState.value as? UiState.ActiveRecording ?: return
        if (!state.isVoiceRecording) {
            Log.d(TAG, "onVoiceNoteStop: ignored — not recording")
            return
        }
        val sessionId    = state.sessionId
        val durationSec  = state.voiceElapsedSeconds.toInt().coerceAtLeast(1)
        val lastPoint    = state.trackPoints.lastOrNull { !it.isAutoPaused }
        val lat          = lastPoint?.latitude  ?: 0.0
        val lng          = lastPoint?.longitude ?: 0.0

        voiceTimerJob?.cancel()
        voiceTimerJob = null

        viewModelScope.launch {
            val path: String
            try {
                path = withContext(deps.ioDispatcher) { deps.audioRecorder.stop() }
            } catch (e: IllegalStateException) {
                // Recording was too short or recorder was in an unexpected state; discard.
                Log.w(TAG, "Voice note stop failed (too short?): ${e.message}")
                updateActiveState { it.copy(isVoiceRecording = false, voiceElapsedSeconds = 0L) }
                return@launch
            }
            val filename = path.substringAfterLast("/")
            Log.d(TAG, "Voice note saved: file=$filename duration=${durationSec}s")

            withContext(deps.ioDispatcher) {
                deps.createVoiceNoteFn(
                    UUID.randomUUID().toString(), sessionId, filename,
                    durationSec, deps.clock(), lat, lng,
                )
            }
            updateActiveState { it.copy(isVoiceRecording = false, voiceElapsedSeconds = 0L) }
            val s = _uiState.value as? UiState.ActiveRecording ?: return@launch
            refreshActiveData(s.sessionId)
        }
    }

    /** User panned the map manually — disable camera auto-follow and show the re-center button. */
    fun onMapPanned() {
        updateActiveState { it.copy(isCameraFollowing = false) }
    }

    /** User tapped the re-center button — resume camera auto-follow. */
    fun onRecenterTapped() {
        updateActiveState { it.copy(isCameraFollowing = true) }
    }

    // ── onCleared ────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        cancelActiveLoops()
        // Always cancel defensively — AudioRecorder.cancel() is a no-op in idle state.
        deps.audioRecorder.cancel()
        Log.d(TAG, "onCleared: loops cancelled, audio recorder released")
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Increments [UiState.ActiveRecording.elapsedSeconds] every second.
     *
     * Delay-first design: the first increment fires after 1 second (not immediately), so the
     * display reads 0 at session start, then 1 after the first second.
     *
     * Using increment-by-1 rather than wall-clock diff keeps unit tests deterministic:
     * `advanceTimeBy(3_000)` always yields `elapsedSeconds == 3`.
     */
    private fun launchTimerLoop() {
        timerJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                updateActiveState { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            }
        }
    }

    /**
     * Polls track points and media/notes every [POLL_INTERVAL_MS] (5 s).
     *
     * Poll-first design: the first fetch fires immediately when the loop starts (no initial
     * delay) so the map polyline and media strip are populated as soon as the session starts,
     * rather than waiting 5 s for the first GPS fix to appear.
     */
    private fun launchPollLoop(sessionId: String) {
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshActiveData(sessionId)
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Fetches the latest track points, media refs, and notes from the DB and updates the state.
     * Must be called from within [viewModelScope] as it uses `withContext`.
     */
    private suspend fun refreshActiveData(sessionId: String) {
        val points = withContext(deps.ioDispatcher) { deps.getTrackPointsFn(sessionId) }
        val refs   = withContext(deps.ioDispatcher) { deps.getMediaRefsFn(sessionId) }
        val notes  = withContext(deps.ioDispatcher) { deps.getNotesFn(sessionId) }
        val items  = buildMediaItems(refs, notes)
        updateActiveState { it.copy(trackPoints = points, recentMedia = items) }
        Log.d(TAG, "Poll: ${points.size} points, ${items.size} media items sessionId=$sessionId")
    }

    /**
     * Increments [UiState.ActiveRecording.voiceElapsedSeconds] every second while a voice note
     * is being recorded. Cancelled by [onVoiceNoteStop] and by [cancelActiveLoops].
     */
    private fun launchVoiceTimerLoop() {
        voiceTimerJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                updateActiveState { it.copy(voiceElapsedSeconds = it.voiceElapsedSeconds + 1) }
            }
        }
    }

    /**
     * Merges [MediaReference]s and [Note]s into a [MediaItem] list, sorted newest-first,
     * capped at [MEDIA_STRIP_MAX].
     */
    private fun buildMediaItems(refs: List<MediaReference>, notes: List<Note>): List<MediaItem> {
        val fromRefs = refs.map { ref ->
            when (ref.type) {
                MediaType.PHOTO -> MediaItem.Photo(ref.id, ref.capturedAt, ref.contentUri ?: "")
                MediaType.VIDEO -> MediaItem.Video(ref.id, ref.capturedAt, ref.contentUri ?: "")
            }
        }
        val fromNotes = notes.map { note ->
            when (note.type) {
                NoteType.TEXT  -> MediaItem.TextNote(
                    id          = note.id,
                    capturedAt  = note.createdAt,
                    preview     = note.content.orEmpty().take(40),
                )
                NoteType.VOICE -> MediaItem.VoiceNote(
                    id              = note.id,
                    capturedAt      = note.createdAt,
                    durationSeconds = note.durationSeconds ?: 0,
                )
            }
        }
        // Sort oldest-first so the grid displays chronologically (upper-left = earliest).
        // takeLast keeps the MEDIA_STRIP_MAX newest items from the oldest-first list,
        // so the displayed set is always the most recent events.
        return (fromRefs + fromNotes)
            .sortedBy { it.capturedAt }
            .takeLast(MEDIA_STRIP_MAX)
    }

    /**
     * Applies [transform] to the current state only if it is [UiState.ActiveRecording].
     * Uses [MutableStateFlow.update] for atomic read-modify-write so concurrent timer and
     * poll coroutines cannot interleave their writes.
     */
    private fun updateActiveState(transform: (UiState.ActiveRecording) -> UiState.ActiveRecording) {
        _uiState.update { state ->
            if (state is UiState.ActiveRecording) transform(state) else state
        }
    }

    private fun cancelActiveLoops() {
        timerJob?.cancel();      timerJob      = null
        pollJob?.cancel();       pollJob       = null
        voiceTimerJob?.cancel(); voiceTimerJob = null
    }

    companion object {
        private const val TAG              = "TripLens/RecordingVM"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MEDIA_STRIP_MAX  = 10
    }
}
