package com.cooldog.triplens.ui.recording

import com.cooldog.triplens.model.MediaReference
import com.cooldog.triplens.model.MediaSource
import com.cooldog.triplens.model.MediaType
import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.platform.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel

/**
 * Unit tests for [RecordingViewModel] active-recording state.
 *
 * All tests reach [RecordingViewModel.UiState.ActiveRecording] via [startSession], which calls
 * `onStartTapped(locationGranted = true)` then [runCurrent].
 *
 * ## Why runCurrent instead of advanceUntilIdle
 * [advanceUntilIdle] advances virtual time until no pending tasks remain. The ViewModel's timer
 * loop (`while(isActive) { delay(1_000L); increment }`) and poll loop
 * (`while(isActive) { refreshData(); delay(5_000L) }`) are infinite, so [advanceUntilIdle] never
 * returns — it keeps advancing through future delay iterations forever.
 *
 * [runCurrent] only runs tasks that are ready at the *current* virtual time (≤ currentTime). It
 * does NOT advance virtual time, so delay-gated future iterations never execute and the call
 * returns promptly.
 *
 * [advanceUntilIdle] is safe **only** after [RecordingViewModel.onStopConfirmed], which calls
 * `cancelActiveLoops()` before any IO work. Once the loops are cancelled their delays resolve with
 * [CancellationException] and terminate, leaving no infinite tasks for [advanceUntilIdle] to chase.
 *
 * [FIXED_EPOCH] = 0L (1970-01-01 UTC) keeps date arithmetic deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelActiveTest {

    private val testDispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<RecordingViewModel>()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private fun runRecordingTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            block()
        } finally {
            viewModels.forEach { it.viewModelScope.cancel() }
            viewModels.clear()
        }
    }

    private val FIXED_EPOCH = 0L

    // ── Captured call arguments ──────────────────────────────────────────────────

    private var capturedTextNoteSessionId: String? = null
    private var capturedTextNoteContent:   String? = null
    private var capturedTextNoteLat:       Double? = null
    private var capturedTextNoteLng:       Double? = null

    private var capturedVoiceFilename:    String? = null
    private var capturedVoiceDuration:    Int?    = null
    private var capturedVoiceSessionId:   String? = null

    private var completeSessionCallCount = 0
    private var stopServiceCallCount     = 0

    // ── Fake data sources ────────────────────────────────────────────────────────

    /** Mutable so tests can inject new points before advancing time past a poll interval. */
    private var fakeTrackPoints: List<TrackPoint> = emptyList()
    private var fakeMediaRefs:   List<MediaReference> = emptyList()
    private var fakeNotes:       List<Note> = emptyList()

    // ── Fake AudioRecorder ───────────────────────────────────────────────────────

    private inner class FakeAudioRecorder : AudioRecorder {
        var startCount  = 0
        var stopCount   = 0
        var cancelCount = 0
        var stopReturnPath = "/data/user/0/com.cooldog.triplens/files/notes/note_test.m4a"
        var throwOnStop = false

        override fun start()         { startCount++ }
        override fun stop(): String  {
            stopCount++
            if (throwOnStop) throw IllegalStateException("recording too short")
            return stopReturnPath
        }
        override fun cancel() { cancelCount++ }
    }

    private lateinit var fakeRecorder: FakeAudioRecorder

    // ── Helper ───────────────────────────────────────────────────────────────────

    private fun buildViewModel() = RecordingViewModel(
        RecordingDeps(
            createGroupFn      = { _, _, _ -> },
            createSessionFn    = { _, _, _, _ -> },
            startService       = { _, _, _ -> },
            getTrackPointsFn   = { fakeTrackPoints },
            getMediaRefsFn     = { fakeMediaRefs },
            getNotesFn         = { fakeNotes },
            createTextNoteFn   = { _, sessionId, content, _, lat, lng ->
                capturedTextNoteSessionId = sessionId
                capturedTextNoteContent   = content
                capturedTextNoteLat       = lat
                capturedTextNoteLng       = lng
            },
            createVoiceNoteFn  = { _, sessionId, filename, duration, _, _, _ ->
                capturedVoiceSessionId = sessionId
                capturedVoiceFilename  = filename
                capturedVoiceDuration  = duration
            },
            completeSessionFn  = { _, _ -> completeSessionCallCount++ },
            stopServiceFn      = { stopServiceCallCount++ },
            audioRecorder      = FakeAudioRecorder().also { fakeRecorder = it },
            clock              = { FIXED_EPOCH },
            ioDispatcher       = testDispatcher,
        )
    ).also { viewModels.add(it) }

    /**
     * Advances the vm to [RecordingViewModel.UiState.ActiveRecording] and returns the state.
     *
     * Uses [runCurrent] rather than [advanceUntilIdle] to avoid advancing virtual time into the
     * infinite timer and poll loops that start with the session. [runCurrent] drains all coroutines
     * that are ready at the current virtual time (the IO work, state transition, first poll) without
     * touching future-scheduled [delay] tasks, so it returns promptly.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.startSession(vm: RecordingViewModel): RecordingViewModel.UiState.ActiveRecording {
        vm.onStartTapped(locationGranted = true)
        runCurrent()  // drains IO + first poll; does NOT advance into timer/poll delay loops
        return assertIs(vm.uiState.value)
    }

    // ── Transition tests ─────────────────────────────────────────────────────────

    @Test
    fun onStartTapped_locationGranted_transitionsToActiveRecording() = runRecordingTest {
        val vm = buildViewModel()
        val state = startSession(vm)
        assertTrue(state.groupName.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    // ── Timer tests ──────────────────────────────────────────────────────────────

    @Test
    fun timer_incrementsElapsedSeconds_eachSecond() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        advanceTimeBy(3_001L)

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals(3L, state.elapsedSeconds)
    }

    @Test
    fun timer_doesNotRunAfterStopConfirmed() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onStopConfirmed()
        // Safe to use advanceUntilIdle here: cancelActiveLoops() removes the infinite timer/poll
        // loops before the IO work runs, so advanceUntilIdle terminates after the finite IO tasks.
        advanceUntilIdle()

        // Advance time — timer loop should have been cancelled, so elapsed stays at 0.
        advanceTimeBy(5_000L)

        // State is now Idle (session ended), so check elapsed is not counting.
        assertIs<RecordingViewModel.UiState.Idle>(vm.uiState.value)
    }

    // ── Poll tests ───────────────────────────────────────────────────────────────

    @Test
    fun poll_firstPollRunsImmediately_populatesTrackPoints() = runRecordingTest {
        fakeTrackPoints = listOf(makeTrackPoint(id = 1L))
        val vm = buildViewModel()
        startSession(vm)  // runCurrent runs first poll

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals(1, state.trackPoints.size)
    }

    @Test
    fun poll_updatesTrackPointsAfterPollInterval() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        // Inject new points that the next poll will return.
        fakeTrackPoints = listOf(makeTrackPoint(1L), makeTrackPoint(2L))
        // advanceTimeBy advances virtual time AND runs tasks scheduled up to that time.
        // The poll delay is 5 000 ms, so the poll at t=5 000 runs and updates state.
        // No advanceUntilIdle here — that would advance into the NEXT poll iteration (t=10 000)
        // and then t=15 000 etc., causing an infinite loop.
        advanceTimeBy(5_001L)

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals(2, state.trackPoints.size)
    }

    @Test
    fun poll_recentMediaCappedAt10() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        fakeNotes = (1..15).map { makeTextNote(id = "note-$it", createdAt = it.toLong()) }
        advanceTimeBy(5_001L)

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals(10, state.recentMedia.size)
    }

    @Test
    fun poll_recentMedia_sortedNewestFirst() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        // Notes with explicit capturedAt values to verify sort order.
        fakeNotes = listOf(
            makeTextNote("note-old", createdAt = 100L),
            makeTextNote("note-new", createdAt = 200L),
        )
        advanceTimeBy(5_001L)

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals("note-new", state.recentMedia.first().id)
        assertEquals("note-old", state.recentMedia.last().id)
    }

    // ── onSaveTextNote tests ──────────────────────────────────────────────────────

    @Test
    fun onSaveTextNote_callsCreateTextNoteFn_withContent() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onSaveTextNote("Great view here!")
        runCurrent()

        assertEquals("Great view here!", capturedTextNoteContent)
    }

    @Test
    fun onSaveTextNote_usesLastNonPausedTrackPointForLocation() = runRecordingTest {
        fakeTrackPoints = listOf(
            makeTrackPoint(id = 1L, lat = 51.5, lng = -0.1, isAutoPaused = false),
        )
        val vm = buildViewModel()
        startSession(vm)  // first poll loads fakeTrackPoints

        vm.onSaveTextNote("test note")
        runCurrent()

        assertEquals(51.5, capturedTextNoteLat)
        assertEquals(-0.1, capturedTextNoteLng)
    }

    @Test
    fun onSaveTextNote_usesZeroZero_whenNoTrackPoints() = runRecordingTest {
        fakeTrackPoints = emptyList()
        val vm = buildViewModel()
        startSession(vm)

        vm.onSaveTextNote("note with no GPS yet")
        runCurrent()

        assertEquals(0.0, capturedTextNoteLat)
        assertEquals(0.0, capturedTextNoteLng)
    }

    @Test
    fun onSaveTextNote_triggersImmediateRefresh_updatesMediaStrip() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        // Simulate a note being visible in the next fetch (after save triggers refresh).
        fakeNotes = listOf(makeTextNote("note-1", createdAt = 999L))

        vm.onSaveTextNote("test")
        runCurrent()

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals(1, state.recentMedia.size)
    }

    // ── Voice note tests ──────────────────────────────────────────────────────────

    @Test
    fun onVoiceNoteStart_setsIsVoiceRecordingTrue() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertTrue(state.isVoiceRecording)
    }

    @Test
    fun onVoiceNoteStart_callsAudioRecorderStart() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        assertEquals(1, fakeRecorder.startCount)
    }

    @Test
    fun voiceTimer_incrementsVoiceElapsedSeconds() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        advanceTimeBy(4_001L)

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertEquals(4L, state.voiceElapsedSeconds)
    }

    @Test
    fun onVoiceNoteStop_callsAudioRecorderStop_andClearsVoiceRecordingState() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        vm.onVoiceNoteStop()
        runCurrent()

        assertEquals(1, fakeRecorder.stopCount)
        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertFalse(state.isVoiceRecording)
        assertEquals(0L, state.voiceElapsedSeconds)
    }

    @Test
    fun onVoiceNoteStop_callsCreateVoiceNoteFn_withBareFilename() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        vm.onVoiceNoteStop()
        runCurrent()

        // Must be the bare filename, not the full path.
        assertEquals("note_test.m4a", capturedVoiceFilename)
    }

    @Test
    fun onVoiceNoteStop_durationMatchesVoiceElapsed() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        // Advance voice timer by 7 seconds before stopping.
        advanceTimeBy(7_001L)

        vm.onVoiceNoteStop()
        runCurrent()

        assertEquals(7, capturedVoiceDuration)
    }

    @Test
    fun onVoiceNoteStop_whenRecorderThrows_clearsVoiceState() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        fakeRecorder.throwOnStop = true
        vm.onVoiceNoteStart()
        runCurrent()
        vm.onVoiceNoteStop()
        runCurrent()

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertFalse(state.isVoiceRecording)
    }

    @Test
    fun onVoiceNoteStart_ignoredIfAlreadyRecording() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()
        vm.onVoiceNoteStart()  // second tap — must be ignored
        runCurrent()

        assertEquals(1, fakeRecorder.startCount)
    }

    // ── Stop confirmation tests ───────────────────────────────────────────────────

    @Test
    fun onStopTapped_emitsShowStopConfirmation() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStopTapped()
        runCurrent()

        assertTrue(RecordingViewModel.Event.ShowStopConfirmation in events)
        job.cancel()
    }

    @Test
    fun onStopTapped_ignoredIfNotActive() = runRecordingTest {
        val vm = buildViewModel()
        // State is Idle — stop tap must be a no-op.
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStopTapped()
        runCurrent()

        assertFalse(RecordingViewModel.Event.ShowStopConfirmation in events)
        job.cancel()
    }

    @Test
    fun onStopConfirmed_callsCompleteSessionAndStopService() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onStopConfirmed()
        advanceUntilIdle()

        assertEquals(1, completeSessionCallCount)
        assertEquals(1, stopServiceCallCount)
    }

    @Test
    fun onStopConfirmed_emitsNavigateToSessionReview() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStopConfirmed()
        advanceUntilIdle()

        assertTrue(events.any { it is RecordingViewModel.Event.NavigateToSessionReview })
        job.cancel()
    }

    @Test
    fun onStopConfirmed_cancelsVoiceRecording_whenActive() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onVoiceNoteStart()
        runCurrent()

        vm.onStopConfirmed()
        advanceUntilIdle()

        assertEquals(1, fakeRecorder.cancelCount)
    }

    @Test
    fun onStopConfirmed_doesNotCancelVoice_whenNotRecording() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onStopConfirmed()
        advanceUntilIdle()

        // No voice recording was active — cancel should NOT have been called.
        assertEquals(0, fakeRecorder.cancelCount)
    }

    // ── Camera follow tests ───────────────────────────────────────────────────────

    @Test
    fun onMapPanned_setsCameraFollowingFalse() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onMapPanned()

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertFalse(state.isCameraFollowing)
    }

    @Test
    fun onRecenterTapped_setsCameraFollowingTrue() = runRecordingTest {
        val vm = buildViewModel()
        startSession(vm)

        vm.onMapPanned()
        vm.onRecenterTapped()

        val state = assertIs<RecordingViewModel.UiState.ActiveRecording>(vm.uiState.value)
        assertTrue(state.isCameraFollowing)
    }

    // ── Factory helpers ──────────────────────────────────────────────────────────

    private fun makeTrackPoint(
        id: Long,
        lat: Double = 48.8566,
        lng: Double = 2.3522,
        isAutoPaused: Boolean = false,
    ) = TrackPoint(
        id            = id,
        sessionId     = "session-1",
        timestamp     = id * 1_000L,
        latitude      = lat,
        longitude     = lng,
        altitude      = null,
        accuracy      = 5f,
        speed         = null,
        transportMode = TransportMode.WALKING,
        isAutoPaused  = isAutoPaused,
    )

    private fun makeTextNote(id: String, createdAt: Long = 0L) = Note(
        id            = id,
        sessionId     = "session-1",
        type          = NoteType.TEXT,
        content       = "Test note $id",
        audioFilename = null,
        durationSeconds = null,
        createdAt     = createdAt,
        latitude      = 0.0,
        longitude     = 0.0,
    )
}
