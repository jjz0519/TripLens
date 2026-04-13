package com.cooldog.triplens.ui

import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for [AppViewModel]'s start destination logic and orphaned session recovery.
 *
 * Uses [StandardTestDispatcher] so coroutines don't execute until [advanceUntilIdle] is called.
 * This allows verifying both the initial [AppViewModel.StartDestination.Loading] state and
 * the resolved state after the IO query completes.
 *
 * No database or Koin is needed: [AppViewModel] accepts lambdas for all injectable behavior
 * so tests supply pure functions rather than real dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    // StandardTestDispatcher: coroutines are scheduled but not executed until explicitly advanced.
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // Override Dispatchers.Main so viewModelScope.launch uses the test dispatcher.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun buildViewModel(
        activeSession: Session?,
        isOnboardingComplete: Boolean = true,  // default keeps existing tests unaffected
        isServiceRunning: Boolean = false,
        resumeOrphanedSessionFn: suspend (String, String) -> Unit = { _, _ -> },
        discardOrphanedSessionFn: (String) -> Unit = {},
    ): AppViewModel = AppViewModel(
        getActiveSessionFn       = { activeSession },
        isOnboardingCompleteFn   = { isOnboardingComplete },
        isServiceRunningFn       = { isServiceRunning },
        resumeOrphanedSessionFn  = resumeOrphanedSessionFn,
        discardOrphanedSessionFn = discardOrphanedSessionFn,
        ioDispatcher             = testDispatcher,
    )

    private val aRecordingSession = Session(
        id        = "s1",
        groupId   = "g1",
        name      = "Day 1",
        startTime = 1_705_312_800_000L,
        endTime   = null,
        status    = SessionStatus.RECORDING,
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun startDestination_isLoading_beforeCoroutineRuns() = runTest(testDispatcher) {
        // StandardTestDispatcher: the init-block launch is queued but not yet executed.
        val vm = buildViewModel(activeSession = null)
        assertEquals(
            AppViewModel.StartDestination.Loading,
            vm.startDestination.value,
            "startDestination must be Loading before the IO coroutine has run",
        )
    }

    @Test
    fun startDestination_isTripList_whenNoActiveSession() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = null)
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.TripList,
            vm.startDestination.value,
            "startDestination must be TripList when getActiveSession returns null",
        )
    }

    @Test
    fun startDestination_isRecording_whenActiveSessionExistsAndServiceRunning() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = true)
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.Recording,
            vm.startDestination.value,
            "startDestination must be Recording when getActiveSession returns a session and service is running",
        )
    }

    @Test
    fun startDestination_isTripList_whenQueryThrows() = runTest(testDispatcher) {
        // DB errors (corruption, permission denial) must not crash the app or leave it on Loading.
        val vm = AppViewModel(
            getActiveSessionFn = { throw RuntimeException("DB corrupted") },
            isOnboardingCompleteFn = { true },   // onboarding complete; only session query fails
            ioDispatcher = testDispatcher,
        )
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.TripList,
            vm.startDestination.value,
            "startDestination must fall back to TripList when the session query throws",
        )
    }

    @Test
    fun startDestination_isOnboarding_whenOnboardingNotComplete() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = null, isOnboardingComplete = false)
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.Onboarding,
            vm.startDestination.value,
            "startDestination must be Onboarding when onboarding has not been completed",
        )
    }

    @Test
    fun startDestination_isTripList_whenOnboardingCompleteAndNoSession() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = null, isOnboardingComplete = true)
        advanceUntilIdle()
        assertEquals(AppViewModel.StartDestination.TripList, vm.startDestination.value)
    }

    @Test
    fun startDestination_isRecording_whenOnboardingCompleteAndSessionActive() = runTest(testDispatcher) {
        val vm = buildViewModel(
            activeSession        = aRecordingSession,
            isOnboardingComplete = true,
            isServiceRunning     = true,
        )
        advanceUntilIdle()
        assertEquals(AppViewModel.StartDestination.Recording, vm.startDestination.value)
    }

    // ------------------------------------------------------------------
    // Orphaned session recovery (Task 18)
    // ------------------------------------------------------------------

    @Test
    fun recoverySession_isSet_whenSessionExistsButServiceNotRunning() = runTest(testDispatcher) {
        // Session in DB but service is gone (neither runningInstance nor SharedPrefs marker).
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = false)
        advanceUntilIdle()
        assertNotNull(
            vm.recoverySession.value,
            "recoverySession must be non-null when session exists but service is not running",
        )
        assertEquals(
            AppViewModel.StartDestination.TripList,
            vm.startDestination.value,
            "startDestination must be TripList (not Recording) when an orphaned session is detected",
        )
    }

    @Test
    fun onRecoveryResume_navigatesToRecordingAndClearsDialog() = runTest(testDispatcher) {
        val resumeCalls = mutableListOf<Pair<String, String>>()
        val vm = buildViewModel(
            activeSession           = aRecordingSession,
            isServiceRunning        = false,
            resumeOrphanedSessionFn = { orphanId, groupId -> resumeCalls += orphanId to groupId },
        )
        advanceUntilIdle()
        assertNotNull(vm.recoverySession.value, "precondition: recovery dialog should be showing")

        vm.onRecoveryResume()
        advanceUntilIdle()

        assertNull(vm.recoverySession.value, "recoverySession must be null after Resume")
        assertEquals(
            AppViewModel.StartDestination.Recording,
            vm.startDestination.value,
            "startDestination must switch to Recording after Resume",
        )
        assertEquals(1, resumeCalls.size, "resumeOrphanedSessionFn must be called exactly once")
        assertEquals("s1" to "g1", resumeCalls.first(), "resumeOrphanedSessionFn called with correct ids")
    }

    @Test
    fun onRecoveryDiscard_staysOnTripListAndClearsDialog() = runTest(testDispatcher) {
        val discardCalls = mutableListOf<String>()
        val vm = buildViewModel(
            activeSession            = aRecordingSession,
            isServiceRunning         = false,
            discardOrphanedSessionFn = { orphanId -> discardCalls += orphanId },
        )
        advanceUntilIdle()
        assertNotNull(vm.recoverySession.value, "precondition: recovery dialog should be showing")

        vm.onRecoveryDiscard()
        advanceUntilIdle()

        assertNull(vm.recoverySession.value, "recoverySession must be null after Discard")
        assertEquals(
            AppViewModel.StartDestination.TripList,
            vm.startDestination.value,
            "startDestination must remain TripList after Discard",
        )
        assertEquals(1, discardCalls.size, "discardOrphanedSessionFn must be called exactly once")
        assertEquals("s1", discardCalls.first(), "discardOrphanedSessionFn called with correct session id")
    }

    @Test
    fun onRecoveryResume_resumeFnThrows_dialogRemainsVisibleForRetry() = runTest(testDispatcher) {
        // If resumeOrphanedSessionFn fails (e.g. service start denied), the dialog must stay
        // visible so the user can retry or choose Discard — the orphaned session must not be
        // silently dropped, which would cause it to re-appear on every cold start instead.
        val vm = buildViewModel(
            activeSession           = aRecordingSession,
            isServiceRunning        = false,
            resumeOrphanedSessionFn = { _, _ -> throw RuntimeException("Service start failed") },
        )
        advanceUntilIdle()
        assertNotNull(vm.recoverySession.value, "precondition: recovery dialog should be showing")

        vm.onRecoveryResume()
        advanceUntilIdle()

        assertNotNull(vm.recoverySession.value, "recoverySession must remain non-null after a failed resume so dialog stays visible")
        assertEquals(
            AppViewModel.StartDestination.TripList,
            vm.startDestination.value,
            "startDestination must remain TripList after a failed resume",
        )
    }

    @Test
    fun recoverySession_isNull_whenSessionExistsAndServiceRunning() = runTest(testDispatcher) {
        // Service is alive (START_STICKY recovered or normal active session) — no recovery needed.
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = true)
        advanceUntilIdle()
        assertNull(
            vm.recoverySession.value,
            "recoverySession must be null when service is running alongside the active session",
        )
    }
}
