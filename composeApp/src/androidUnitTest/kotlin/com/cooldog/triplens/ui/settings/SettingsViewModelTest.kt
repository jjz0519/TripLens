package com.cooldog.triplens.ui.settings

import com.cooldog.triplens.data.AppPreferences
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.data.ScanInterval
import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SettingsViewModel] (Task 16).
 *
 * Uses [FakeAppPreferences] — no Android context, no real DataStore.
 *
 * Tests verify:
 * - Initial StateFlow values match DataStore defaults after [advanceUntilIdle] (the init
 *   block launches a coroutine that populates values from [FakeAppPreferences]).
 * - Selecting each preference writes to the fake and updates the exposed StateFlow.
 * - [notifyServiceFn] is called only when a session is active during accuracy profile change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fake collaborators ────────────────────────────────────────────────────

    private class FakeAppPreferences(
        var language:        Language        = Language.SYSTEM,
        var accuracyProfile: AccuracyProfile = AccuracyProfile.STANDARD,
        var scanInterval:    ScanInterval    = ScanInterval.STANDARD,
    ) : AppPreferences {
        var onboardingComplete = false
        override suspend fun isOnboardingComplete()                    = onboardingComplete
        override suspend fun setOnboardingComplete()                   { onboardingComplete = true }
        override suspend fun getLanguage()                             = language
        override suspend fun setLanguage(l: Language)                  { language = l }
        override suspend fun getAccuracyProfile()                      = accuracyProfile
        override suspend fun setAccuracyProfile(p: AccuracyProfile)    { accuracyProfile = p }
        override suspend fun getScanInterval()                         = scanInterval
        override suspend fun setScanInterval(i: ScanInterval)          { scanInterval = i }
    }

    private fun buildViewModel(
        prefs: FakeAppPreferences = FakeAppPreferences(),
        isSessionActive: Boolean = false,
        notifyServiceCallCount: (AccuracyProfile) -> Unit = {},
        appliedLocales: MutableList<Language> = mutableListOf(),
    ) = SettingsViewModel(
        appPreferences    = prefs,
        isSessionActiveFn = { isSessionActive },
        notifyServiceFn   = notifyServiceCallCount,
        applyLocaleFn     = { appliedLocales.add(it) },
        ioDispatcher      = testDispatcher,
    )

    // ── Language tests ────────────────────────────────────────────────────────

    @Test
    fun languageFlow_initiallyEmitsSystem() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeAppPreferences(language = Language.SYSTEM))
        advanceUntilIdle()
        assertEquals(Language.SYSTEM, vm.language.value)
    }

    @Test
    fun onLanguageSelected_ZhCn_updatesFlowAndPrefs() = runTest(testDispatcher) {
        val prefs = FakeAppPreferences()
        val applied = mutableListOf<Language>()
        val vm = buildViewModel(prefs, appliedLocales = applied)
        advanceUntilIdle()

        vm.onLanguageSelected(Language.ZH_CN)
        advanceUntilIdle()

        assertEquals(Language.ZH_CN, vm.language.value, "StateFlow must reflect ZH_CN")
        assertEquals(Language.ZH_CN, prefs.language, "DataStore must be written with ZH_CN")
        assertEquals(listOf(Language.ZH_CN), applied, "applyLocaleFn must be called once with ZH_CN")
    }

    // ── GPS Accuracy tests ────────────────────────────────────────────────────

    @Test
    fun accuracyProfileFlow_initiallyEmitsStandard() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeAppPreferences(accuracyProfile = AccuracyProfile.STANDARD))
        advanceUntilIdle()
        assertEquals(AccuracyProfile.STANDARD, vm.accuracyProfile.value)
    }

    @Test
    fun onAccuracyProfileSelected_BatterySaver_updatesFlowAndPrefs() = runTest(testDispatcher) {
        val prefs = FakeAppPreferences()
        val vm = buildViewModel(prefs)
        advanceUntilIdle()

        vm.onAccuracyProfileSelected(AccuracyProfile.BATTERY_SAVER)
        advanceUntilIdle()

        assertEquals(AccuracyProfile.BATTERY_SAVER, vm.accuracyProfile.value)
        assertEquals(AccuracyProfile.BATTERY_SAVER, prefs.accuracyProfile)
    }

    // ── Scan interval tests ───────────────────────────────────────────────────

    @Test
    fun scanIntervalFlow_initiallyEmitsStandard() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeAppPreferences(scanInterval = ScanInterval.STANDARD))
        advanceUntilIdle()
        assertEquals(ScanInterval.STANDARD, vm.scanInterval.value)
    }

    @Test
    fun onScanIntervalSelected_Short_updatesFlowAndPrefs() = runTest(testDispatcher) {
        val prefs = FakeAppPreferences()
        val vm = buildViewModel(prefs)
        advanceUntilIdle()

        vm.onScanIntervalSelected(ScanInterval.SHORT)
        advanceUntilIdle()

        assertEquals(ScanInterval.SHORT, vm.scanInterval.value)
        assertEquals(ScanInterval.SHORT, prefs.scanInterval)
    }

    // ── Service notification tests ────────────────────────────────────────────

    @Test
    fun onAccuracyProfileSelected_noActiveSession_doesNotNotifyService() = runTest(testDispatcher) {
        val notified = mutableListOf<AccuracyProfile>()
        val vm = buildViewModel(isSessionActive = false, notifyServiceCallCount = { notified.add(it) })
        advanceUntilIdle()

        vm.onAccuracyProfileSelected(AccuracyProfile.HIGH)
        advanceUntilIdle()

        assertTrue(notified.isEmpty(), "notifyServiceFn must NOT be called when no session is active")
    }

    @Test
    fun onAccuracyProfileSelected_withActiveSession_notifiesServiceWithNewProfile() = runTest(testDispatcher) {
        val notified = mutableListOf<AccuracyProfile>()
        val vm = buildViewModel(isSessionActive = true, notifyServiceCallCount = { notified.add(it) })
        advanceUntilIdle()

        vm.onAccuracyProfileSelected(AccuracyProfile.BATTERY_SAVER)
        advanceUntilIdle()

        assertEquals(listOf(AccuracyProfile.BATTERY_SAVER), notified,
            "notifyServiceFn must be called once with BATTERY_SAVER when a session is active")
    }
}
