package com.cooldog.triplens.ui.onboarding

import com.cooldog.triplens.data.AppPreferences
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.data.ScanInterval
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
import kotlin.test.assertTrue

/**
 * Unit tests for [OnboardingViewModel].
 *
 * Uses [FakeAppPreferences] — no Android context, no real DataStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private class FakeAppPreferences : AppPreferences {
        var onboardingComplete = false
        override suspend fun isOnboardingComplete() = onboardingComplete
        override suspend fun setOnboardingComplete() { onboardingComplete = true }
        // Task 16 additions — not exercised by onboarding tests; stubs return defaults.
        override suspend fun getLanguage()                        = Language.SYSTEM
        override suspend fun setLanguage(language: Language)      = Unit
        override suspend fun getAccuracyProfile()                 = AccuracyProfile.STANDARD
        override suspend fun setAccuracyProfile(profile: AccuracyProfile) = Unit
        override suspend fun getScanInterval()                    = ScanInterval.STANDARD
        override suspend fun setScanInterval(interval: ScanInterval)      = Unit
    }

    private fun buildViewModel(prefs: FakeAppPreferences = FakeAppPreferences()) =
        OnboardingViewModel(appPreferences = prefs, ioDispatcher = testDispatcher)

    @Test
    fun initialState_isShowPermissions() = runTest(testDispatcher) {
        val vm = buildViewModel()
        assertEquals(OnboardingViewModel.UiState.ShowPermissions, vm.uiState.value)
    }

    @Test
    fun onPermissionsHandled_writesOnboardingCompleteToPrefs() = runTest(testDispatcher) {
        val prefs = FakeAppPreferences()
        val vm = buildViewModel(prefs)

        vm.onPermissionsHandled()
        advanceUntilIdle()

        assertTrue(prefs.onboardingComplete, "onboardingComplete must be true after onPermissionsHandled()")
    }

    @Test
    fun onPermissionsHandled_emitsNavigateToTripListEvent() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val events = mutableListOf<OnboardingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onPermissionsHandled()
        advanceUntilIdle()

        assertEquals(listOf<OnboardingViewModel.Event>(OnboardingViewModel.Event.NavigateToTripList), events)
        job.cancel()
    }
}
