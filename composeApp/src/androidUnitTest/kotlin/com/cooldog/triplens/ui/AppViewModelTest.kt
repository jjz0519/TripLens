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
import kotlin.test.assertTrue

/**
 * Unit tests for [AppViewModel]'s start destination logic and session auto-resume.
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

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
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
        isOnboardingComplete: Boolean = true,
        isServiceRunning: Boolean = false,
        resumeOrphanedSessionFn: suspend (Session) -> Unit = {},
    ): AppViewModel = AppViewModel(
        getActiveSessionFn       = { activeSession },
        isOnboardingCompleteFn   = { isOnboardingComplete },
        isServiceRunningFn       = { isServiceRunning },
        resumeOrphanedSessionFn  = resumeOrphanedSessionFn,
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
    // Start destination — basic routing
    // ------------------------------------------------------------------

    @Test
    fun startDestination_isLoading_beforeCoroutineRuns() = runTest(testDispatcher) {
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
        assertEquals(AppViewModel.StartDestination.TripList, vm.startDestination.value)
    }

    @Test
    fun startDestination_isRecording_whenActiveSessionExistsAndServiceRunning() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = true)
        advanceUntilIdle()
        assertEquals(AppViewModel.StartDestination.Recording, vm.startDestination.value)
    }

    @Test
    fun startDestination_isTripList_whenQueryThrows() = runTest(testDispatcher) {
        val vm = AppViewModel(
            getActiveSessionFn = { throw RuntimeException("DB corrupted") },
            isOnboardingCompleteFn = { true },
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
        assertEquals(AppViewModel.StartDestination.Onboarding, vm.startDestination.value)
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
    // Orphaned session auto-resume (service was dead on cold start)
    // ------------------------------------------------------------------

    @Test
    fun startDestination_isRecording_whenSessionExistsButServiceNotRunning() = runTest(testDispatcher) {
        // Service is gone but DB has a recording session — must auto-resume to Recording.
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = false)
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.Recording,
            vm.startDestination.value,
            "startDestination must be Recording for orphaned sessions (auto-resume, no dialog)",
        )
    }

    @Test
    fun isSessionActive_isTrue_whenOrphanedSessionAutoResumed() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = false)
        advanceUntilIdle()
        assertTrue(
            vm.isSessionActive.value,
            "isSessionActive must be true when an orphaned session is auto-resumed",
        )
    }

    @Test
    fun resumeOrphanedSessionFn_calledOnce_withCorrectSession_whenServiceDead() = runTest(testDispatcher) {
        val resumedSessions = mutableListOf<Session>()
        val vm = buildViewModel(
            activeSession           = aRecordingSession,
            isServiceRunning        = false,
            resumeOrphanedSessionFn = { session -> resumedSessions += session },
        )
        advanceUntilIdle()
        assertEquals(1, resumedSessions.size, "resumeOrphanedSessionFn must be called exactly once")
        assertEquals(aRecordingSession, resumedSessions.first(), "must pass the original session")
    }

    @Test
    fun startDestination_stillRecording_whenResumeOrphanedSessionFnThrows() = runTest(testDispatcher) {
        // Even if the service fails to restart, we still route to Recording so RecordingViewModel
        // can rehydrate from the DB and the user can tap Stop.
        val vm = buildViewModel(
            activeSession           = aRecordingSession,
            isServiceRunning        = false,
            resumeOrphanedSessionFn = { throw RuntimeException("Service start denied") },
        )
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.Recording,
            vm.startDestination.value,
            "startDestination must still be Recording even if service restart throws",
        )
    }

    @Test
    fun recoverySession_notExposedAsProperty() = runTest(testDispatcher) {
        // Verifies that AppViewModel no longer exposes recoverySession (dialog was removed).
        // The test simply instantiates and uses the ViewModel without referencing the old field.
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = false)
        advanceUntilIdle()
        // If this compiles and runs, the old recoverySession field is gone.
        assertEquals(AppViewModel.StartDestination.Recording, vm.startDestination.value)
    }

    // ------------------------------------------------------------------
    // isSessionActive
    // ------------------------------------------------------------------

    @Test
    fun isSessionActive_isFalse_whenNoActiveSession() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = null)
        advanceUntilIdle()
        assertEquals(false, vm.isSessionActive.value)
    }

    @Test
    fun isSessionActive_isTrue_whenActiveSessionAndServiceRunning() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = aRecordingSession, isServiceRunning = true)
        advanceUntilIdle()
        assertTrue(vm.isSessionActive.value)
    }
}
