package com.cooldog.triplens.ui.recording

import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Unit tests for [RecordingViewModel].
 *
 * [RecordingViewModel] takes lambdas for all external calls (DB writes, service start) so tests
 * run entirely on the JVM — no Android context, Koin, or database required.
 *
 * [FIXED_EPOCH] = 0L (1970-01-01 UTC) keeps date arithmetic unambiguous across timezones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private val FIXED_EPOCH = 0L  // 1970-01-01 UTC

    // Captured call arguments.
    private var capturedGroupId: String? = null
    private var capturedGroupName: String? = null
    private var capturedGroupNow: Long? = null
    private var capturedSessionId: String? = null
    private var capturedSessionGroupId: String? = null
    private var capturedSessionName: String? = null
    private var capturedSessionStartTime: Long? = null
    private var capturedServiceSessionId: String? = null
    private var capturedServiceProfile: AccuracyProfile? = null
    private var createGroupCallCount = 0

    private fun buildViewModel() = RecordingViewModel(
        createGroupFn = { id, name, now ->
            createGroupCallCount++
            capturedGroupId = id
            capturedGroupName = name
            capturedGroupNow = now
        },
        createSessionFn = { id, groupId, name, startTime ->
            capturedSessionId = id
            capturedSessionGroupId = groupId
            capturedSessionName = name
            capturedSessionStartTime = startTime
        },
        startService = { sessionId, profile ->
            capturedServiceSessionId = sessionId
            capturedServiceProfile = profile
        },
        clock = { FIXED_EPOCH },
        ioDispatcher = testDispatcher,
    )

    @Test
    fun initialState_isIdle() = runTest(testDispatcher) {
        val vm = buildViewModel()
        assertEquals(RecordingViewModel.UiState.Idle, vm.uiState.value)
    }

    @Test
    fun onStartTapped_locationDenied_staysIdle_andEmitsShowRationale() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStartTapped(locationGranted = false)
        advanceUntilIdle()

        assertEquals(RecordingViewModel.UiState.Idle, vm.uiState.value)
        assertEquals(listOf<RecordingViewModel.Event>(RecordingViewModel.Event.ShowPermissionRationale), events)
        job.cancel()
    }

    @Test
    fun onStartTapped_locationGranted_transitionsToStartingSession() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()
        assertEquals(RecordingViewModel.UiState.StartingSession, vm.uiState.value)
    }

    @Test
    fun onStartTapped_locationGranted_createsTripGroupWithTodayDate() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertNotNull(capturedGroupId)
        // epoch 0 in UTC = 1970-01-01
        assertEquals("1970-01-01", capturedGroupName, "TripGroup name must be today's date in yyyy-MM-dd UTC")
        assertEquals(FIXED_EPOCH, capturedGroupNow)
    }

    @Test
    fun onStartTapped_locationGranted_createsSessionLinkedToGroup() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertNotNull(capturedSessionId)
        assertEquals(capturedGroupId, capturedSessionGroupId, "Session groupId must match the auto-created TripGroup id")
        assertEquals("Session 1", capturedSessionName)
        assertEquals(FIXED_EPOCH, capturedSessionStartTime)
    }

    @Test
    fun onStartTapped_locationGranted_startsServiceWithStandardProfile() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertNotNull(capturedServiceSessionId)
        assertEquals(capturedSessionId, capturedServiceSessionId, "startService sessionId must match the created session id")
        assertEquals(AccuracyProfile.STANDARD, capturedServiceProfile)
    }

    @Test
    fun onStartTapped_locationGranted_emitsNavigateToActiveRecording() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertTrue(
            RecordingViewModel.Event.NavigateToActiveRecording in events,
            "NavigateToActiveRecording must be emitted after session creation",
        )
        job.cancel()
    }

    @Test
    fun onStartTapped_calledTwiceWhileStarting_createsOnlyOneGroup() = runTest(testDispatcher) {
        val vm = buildViewModel()

        vm.onStartTapped(locationGranted = true)
        vm.onStartTapped(locationGranted = true)  // second tap while StartingSession
        advanceUntilIdle()

        assertEquals(1, createGroupCallCount, "createGroup must be called exactly once even if tapped twice")
    }
}
