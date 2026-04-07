package com.cooldog.triplens.ui.recording

import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.platform.AudioRecorder
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RecordingViewModel] idle-state behavior (Task 12 coverage).
 *
 * Active-state tests live in [RecordingViewModelActiveTest].
 *
 * [RecordingViewModel] takes a [RecordingDeps] bundle so all external calls can be
 * replaced with pure lambdas — no Android runtime, Koin, or database required.
 *
 * [FIXED_EPOCH] = 0L (1970-01-01 UTC) keeps date arithmetic unambiguous across timezones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

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

    // Captured call arguments.
    private var capturedGroupId:          String? = null
    private var capturedGroupName:        String? = null
    private var capturedGroupNow:         Long?   = null
    private var capturedSessionId:        String? = null
    private var capturedSessionGroupId:   String? = null
    private var capturedSessionName:      String? = null
    private var capturedSessionStartTime: Long?   = null
    private var capturedServiceSessionId: String? = null
    private var capturedServiceProfile:   AccuracyProfile? = null
    private var createGroupCallCount = 0

    /** No-op AudioRecorder so idle tests compile without caring about voice recording. */
    private val noOpRecorder = object : AudioRecorder {
        override fun start()         = Unit
        override fun stop(): String  = ""
        override fun cancel()        = Unit
    }

    private fun buildViewModel() = RecordingViewModel(
        RecordingDeps(
            createGroupFn    = { id, name, now ->
                createGroupCallCount++
                capturedGroupId   = id
                capturedGroupName = name
                capturedGroupNow  = now
            },
            createSessionFn  = { id, groupId, name, startTime ->
                capturedSessionId        = id
                capturedSessionGroupId   = groupId
                capturedSessionName      = name
                capturedSessionStartTime = startTime
            },
            startService     = { sessionId, profile, _ ->
                capturedServiceSessionId = sessionId
                capturedServiceProfile   = profile
            },
            // Active-state dependencies are no-ops — idle tests never enter ActiveRecording.
            getTrackPointsFn  = { emptyList() },
            getMediaRefsFn    = { emptyList() },
            getNotesFn        = { emptyList() },
            createTextNoteFn  = { _, _, _, _, _, _ -> },
            createVoiceNoteFn = { _, _, _, _, _, _, _ -> },
            completeSessionFn = { _, _ -> },
            setDistanceFn     = { _, _ -> },
            stopServiceFn     = {},
            audioRecorder     = noOpRecorder,
            clock             = { FIXED_EPOCH },
            ioDispatcher      = testDispatcher,
        )
    ).also { viewModels.add(it) }

    @Test
    fun initialState_isIdle() = runRecordingTest {
        val vm = buildViewModel()
        assertEquals(RecordingViewModel.UiState.Idle, vm.uiState.value)
    }

    @Test
    fun onStartTapped_locationDenied_staysIdle_andEmitsShowRationale() = runRecordingTest {
        val vm = buildViewModel()
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStartTapped(locationGranted = false)
        runCurrent()

        assertEquals(RecordingViewModel.UiState.Idle, vm.uiState.value)
        assertEquals(listOf<RecordingViewModel.Event>(RecordingViewModel.Event.ShowPermissionRationale), events)
        job.cancel()
    }

    @Test
    fun onStartTapped_locationGranted_transitionsToStartingSession() = runRecordingTest {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        // Before IO completes the state must be StartingSession.
        assertEquals(RecordingViewModel.UiState.StartingSession, vm.uiState.value)
    }

    @Test
    fun onStartTapped_locationGranted_createsTripGroupWithTodayDate() = runRecordingTest {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        runCurrent()

        assertNotNull(capturedGroupId)
        // epoch 0 in UTC = 1970-01-01
        assertEquals("1970-01-01", capturedGroupName, "TripGroup name must be today's date in yyyy-MM-dd UTC")
        assertEquals(FIXED_EPOCH, capturedGroupNow)
    }

    @Test
    fun onStartTapped_locationGranted_createsSessionLinkedToGroup() = runRecordingTest {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        runCurrent()

        assertNotNull(capturedSessionId)
        assertEquals(capturedGroupId, capturedSessionGroupId, "Session groupId must match the auto-created TripGroup id")
        assertEquals("Session 1", capturedSessionName)
        assertEquals(FIXED_EPOCH, capturedSessionStartTime)
    }

    @Test
    fun onStartTapped_locationGranted_startsServiceWithStandardProfile() = runRecordingTest {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        runCurrent()

        assertNotNull(capturedServiceSessionId)
        assertEquals(capturedSessionId, capturedServiceSessionId, "startService sessionId must match the created session id")
        assertEquals(AccuracyProfile.STANDARD, capturedServiceProfile)
    }

    @Test
    fun onStartTapped_locationGranted_emitsNavigateToActiveRecording() = runRecordingTest {
        val vm = buildViewModel()
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStartTapped(locationGranted = true)
        runCurrent()

        assertTrue(
            RecordingViewModel.Event.NavigateToActiveRecording in events,
            "NavigateToActiveRecording must be emitted after session creation",
        )
        job.cancel()
    }

    @Test
    fun onStartTapped_calledTwiceWhileStarting_createsOnlyOneGroup() = runRecordingTest {
        val vm = buildViewModel()

        vm.onStartTapped(locationGranted = true)
        vm.onStartTapped(locationGranted = true)  // second tap while StartingSession
        runCurrent()

        assertEquals(1, createGroupCallCount, "createGroup must be called exactly once even if tapped twice")
    }
}
