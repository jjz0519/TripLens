package com.cooldog.triplens.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.data.AppPreferences
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.data.ScanInterval
import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Settings screen (Task 16).
 *
 * ## Responsibilities
 * - Load all preference values from [AppPreferences] on creation and expose them as [StateFlow]s.
 * - Write preference changes back to [AppPreferences] on user interaction.
 * - Apply language changes immediately via [applyLocaleFn] (wraps AppCompatDelegate on the call site).
 * - Notify [LocationTrackingService] when the accuracy profile changes while a session is active.
 *
 * ## Why lambdas instead of direct service/locale calls?
 * Both [applyLocaleFn] and [notifyServiceFn] require Android Context/Intent construction. Keeping
 * them as injected lambdas means the ViewModel stays pure and unit-testable with a fake.
 *
 * ## Threading
 * DataStore reads and writes always run on [ioDispatcher] (IO-bound). [applyLocaleFn] and
 * [notifyServiceFn] are called on the main dispatcher (the default after [withContext] returns)
 * so that AppCompatDelegate and startService() receive a main-thread call.
 */
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    /**
     * Returns true when a recording session is currently active. Used to decide whether
     * to notify [LocationTrackingService] of an accuracy profile change.
     */
    private val isSessionActiveFn: suspend () -> Boolean,
    /**
     * Sends an ACTION_UPDATE_PROFILE intent to [LocationTrackingService] so the new profile
     * takes effect immediately on the running session's GPS interval.
     */
    private val notifyServiceFn: (AccuracyProfile) -> Unit,
    /**
     * Calls [AppCompatDelegate.setApplicationLocales] with the appropriate [LocaleListCompat]
     * so the UI language updates in-process without an app restart (API 33+) or via activity
     * recreation on older versions.
     */
    private val applyLocaleFn: (Language) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    // Default values match DataStore defaults — will be overwritten once loadPreferences() runs.
    private val _language        = MutableStateFlow(Language.SYSTEM)
    private val _accuracyProfile = MutableStateFlow(AccuracyProfile.STANDARD)
    private val _scanInterval    = MutableStateFlow(ScanInterval.STANDARD)

    val language:        StateFlow<Language>        = _language.asStateFlow()
    val accuracyProfile: StateFlow<AccuracyProfile> = _accuracyProfile.asStateFlow()
    val scanInterval:    StateFlow<ScanInterval>    = _scanInterval.asStateFlow()

    init {
        loadPreferences()
    }

    /**
     * Reads all three preference values from DataStore. Called once in [init].
     *
     * The StateFlows briefly show their initialisation defaults (SYSTEM / STANDARD / STANDARD)
     * until the DataStore read completes; this is acceptable because the reads are fast (< 1 ms
     * for in-memory DataStore on a warm device).
     */
    private fun loadPreferences() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                _language.value        = appPreferences.getLanguage()
                _accuracyProfile.value = appPreferences.getAccuracyProfile()
                _scanInterval.value    = appPreferences.getScanInterval()
            }
            Log.d(TAG, "Loaded preferences: language=${_language.value}, " +
                    "profile=${_accuracyProfile.value}, scanInterval=${_scanInterval.value}")
        }
    }

    /**
     * Persists [language] and immediately applies it via [applyLocaleFn].
     * Called on main dispatcher after the DataStore write so AppCompatDelegate receives a
     * main-thread call.
     */
    fun onLanguageSelected(language: Language) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                appPreferences.setLanguage(language)
            }
            _language.value = language
            applyLocaleFn(language)
            Log.i(TAG, "Language changed to $language")
        }
    }

    /**
     * Persists [profile] and, if a session is currently recording, notifies the service so the
     * GPS interval updates immediately without waiting for the next session start.
     */
    fun onAccuracyProfileSelected(profile: AccuracyProfile) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                appPreferences.setAccuracyProfile(profile)
            }
            _accuracyProfile.value = profile
            val isActive = withContext(ioDispatcher) { isSessionActiveFn() }
            if (isActive) {
                notifyServiceFn(profile)
                Log.i(TAG, "Accuracy profile → $profile (notified active service)")
            } else {
                Log.i(TAG, "Accuracy profile → $profile (no active session)")
            }
        }
    }

    /** Persists [interval]. Takes effect at the next recording session start. */
    fun onScanIntervalSelected(interval: ScanInterval) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                appPreferences.setScanInterval(interval)
            }
            _scanInterval.value = interval
            Log.i(TAG, "Gallery scan interval → ${interval.seconds}s")
        }
    }

    companion object {
        private const val TAG = "TripLens/SettingsVM"
    }
}
