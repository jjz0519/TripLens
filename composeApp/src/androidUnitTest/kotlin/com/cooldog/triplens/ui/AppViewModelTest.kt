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

/**
 * Unit tests for [AppViewModel]'s start destination logic.
 *
 * Uses [StandardTestDispatcher] so coroutines don't execute until [advanceUntilIdle] is called.
 * This allows verifying both the initial [AppViewModel.StartDestination.Loading] state and
 * the resolved state after the IO query completes.
 *
 * No database or Koin is needed: [AppViewModel] accepts lambdas for [getActiveSessionFn] and
 * [isOnboardingCompleteFn] so tests supply pure functions rather than real dependencies.
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
    ): AppViewModel = AppViewModel(
        getActiveSessionFn = { activeSession },
        isOnboardingCompleteFn = { isOnboardingComplete },
        ioDispatcher = testDispatcher,
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
    fun startDestination_isRecording_whenActiveSessionExists() = runTest(testDispatcher) {
        val vm = buildViewModel(activeSession = aRecordingSession)
        advanceUntilIdle()
        assertEquals(
            AppViewModel.StartDestination.Recording,
            vm.startDestination.value,
            "startDestination must be Recording when getActiveSession returns a non-null session",
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
        val vm = buildViewModel(activeSession = aRecordingSession, isOnboardingComplete = true)
        advanceUntilIdle()
        assertEquals(AppViewModel.StartDestination.Recording, vm.startDestination.value)
    }
}
