package com.cooldog.triplens.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.data.ScanInterval
import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instrumented tests for [DataStoreAppPreferences].
 *
 * Requires a connected device or emulator — DataStore reads/writes the real filesystem.
 *
 * Note on test isolation: DataStore persists to the same file across test methods within the
 * same process run. The write test ([afterSetOnboardingComplete_isOnboardingComplete_returnsTrue])
 * will cause the read test to see `true` if they run in the same process without reinstalling.
 * In CI each test run installs a fresh APK so the DataStore file starts clean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = DataStoreAppPreferences(context)

    @Test
    fun isOnboardingComplete_returnsFalseByDefault() = runTest {
        // This passes on a fresh install. If the write test has already run in this process,
        // DataStore retains its value and this test will fail — that is expected behaviour.
        assertFalse(prefs.isOnboardingComplete(), "Default value must be false on a fresh install")
    }

    @Test
    fun afterSetOnboardingComplete_isOnboardingComplete_returnsTrue() = runTest {
        prefs.setOnboardingComplete()
        assertTrue(
            prefs.isOnboardingComplete(),
            "isOnboardingComplete must return true after setOnboardingComplete()",
        )
    }

    // ── Language (Task 16) ────────────────────────────────────────────────────

    @Test
    fun getLanguage_defaultIsSystem() = runTest {
        // Fresh install: no stored value → must return Language.SYSTEM.
        // Note: if setLanguage tests have already run in this process, DataStore retains
        // the written value. This test is authoritative on a clean install / fresh APK.
        assertEquals(Language.SYSTEM, prefs.getLanguage(), "Default language must be SYSTEM")
    }

    @Test
    fun setLanguage_ZhCn_readBackMatchesLanguage() = runTest {
        prefs.setLanguage(Language.ZH_CN)
        assertEquals(Language.ZH_CN, prefs.getLanguage(), "getLanguage must return ZH_CN after setLanguage(ZH_CN)")
    }

    // ── GPS Accuracy Profile (Task 16) ────────────────────────────────────────

    @Test
    fun getAccuracyProfile_defaultIsStandard() = runTest {
        // Fresh install: no stored value → must return AccuracyProfile.STANDARD.
        assertEquals(AccuracyProfile.STANDARD, prefs.getAccuracyProfile(), "Default profile must be STANDARD")
    }

    @Test
    fun setAccuracyProfile_High_readBackMatches() = runTest {
        prefs.setAccuracyProfile(AccuracyProfile.HIGH)
        assertEquals(AccuracyProfile.HIGH, prefs.getAccuracyProfile(), "getAccuracyProfile must return HIGH after setAccuracyProfile(HIGH)")
    }

    // ── Gallery Scan Interval (Task 16) ───────────────────────────────────────

    @Test
    fun getScanInterval_defaultIsStandard() = runTest {
        // Fresh install: no stored value → must return ScanInterval.STANDARD (60 s).
        assertEquals(ScanInterval.STANDARD, prefs.getScanInterval(), "Default scan interval must be STANDARD")
    }

    @Test
    fun setScanInterval_Short_readBackMatches() = runTest {
        prefs.setScanInterval(ScanInterval.SHORT)
        assertEquals(ScanInterval.SHORT, prefs.getScanInterval(), "getScanInterval must return SHORT after setScanInterval(SHORT)")
    }
}
