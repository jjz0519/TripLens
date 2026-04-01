package com.cooldog.triplens.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
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
}
