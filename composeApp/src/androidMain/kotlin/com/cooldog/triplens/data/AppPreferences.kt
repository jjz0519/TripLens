package com.cooldog.triplens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Top-level delegate — one DataStore per named file per process.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "triplens_prefs")

/**
 * Interface over persisted app preferences.
 *
 * Extracted as an interface so ViewModel tests can inject a fake without touching real DataStore
 * (which requires an Android context).
 *
 * ## Keys (added in Task 16)
 * - [getLanguage] / [setLanguage]: per-app display language. Default: [Language.SYSTEM].
 * - [getAccuracyProfile] / [setAccuracyProfile]: GPS accuracy profile for new sessions.
 *   Default: [AccuracyProfile.STANDARD]. Changing while a session is active takes effect
 *   immediately via [LocationTrackingService] ACTION_UPDATE_PROFILE intent.
 * - [getScanInterval] / [setScanInterval]: how often the gallery is scanned during recording.
 *   Default: [ScanInterval.STANDARD] (60 s). Takes effect at the next session start.
 */
interface AppPreferences {

    // ── Onboarding ────────────────────────────────────────────────────────────

    /** Returns true if first-launch permission onboarding has been completed. Default: false. */
    suspend fun isOnboardingComplete(): Boolean

    /** Marks onboarding as complete. Idempotent — safe to call multiple times. */
    suspend fun setOnboardingComplete()

    // ── Language ──────────────────────────────────────────────────────────────

    /** Returns the saved display language. Default: [Language.SYSTEM]. */
    suspend fun getLanguage(): Language

    /** Persists [language] and (via [SettingsViewModel]) immediately calls AppCompatDelegate. */
    suspend fun setLanguage(language: Language)

    // ── GPS Accuracy ──────────────────────────────────────────────────────────

    /** Returns the saved GPS accuracy profile. Default: [AccuracyProfile.STANDARD]. */
    suspend fun getAccuracyProfile(): AccuracyProfile

    /** Persists [profile]. Active sessions are notified via LocationTrackingService intent. */
    suspend fun setAccuracyProfile(profile: AccuracyProfile)

    // ── Gallery Scan Interval ─────────────────────────────────────────────────

    /** Returns the saved gallery scan interval. Default: [ScanInterval.STANDARD] (60 s). */
    suspend fun getScanInterval(): ScanInterval

    /** Persists [interval]. Takes effect at the next recording session start. */
    suspend fun setScanInterval(interval: ScanInterval)
}

/**
 * Production implementation backed by AndroidX DataStore Preferences.
 *
 * Registered as a singleton in [com.cooldog.triplens.di.AndroidModule]: DataStore must not be
 * opened more than once per file per process, so a singleton is required.
 *
 * All enum values are stored by [name] (a stable string). Unknown stored names (e.g. after
 * a downgrade) silently fall back to the respective default — no crash, no data loss.
 */
class DataStoreAppPreferences(private val context: Context) : AppPreferences {

    // ── Onboarding ────────────────────────────────────────────────────────────

    override suspend fun isOnboardingComplete(): Boolean =
        context.dataStore.data
            .map { prefs -> prefs[ONBOARDING_COMPLETE_KEY] ?: false }
            .first()

    override suspend fun setOnboardingComplete() {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETE_KEY] = true
        }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    override suspend fun getLanguage(): Language =
        context.dataStore.data
            .map { prefs ->
                prefs[LANGUAGE_KEY]
                    ?.let { runCatching { Language.valueOf(it) }.getOrNull() }
                    ?: Language.SYSTEM
            }
            .first()

    override suspend fun setLanguage(language: Language) {
        context.dataStore.edit { prefs ->
            prefs[LANGUAGE_KEY] = language.name
        }
    }

    // ── GPS Accuracy ──────────────────────────────────────────────────────────

    override suspend fun getAccuracyProfile(): AccuracyProfile =
        context.dataStore.data
            .map { prefs ->
                prefs[ACCURACY_PROFILE_KEY]
                    ?.let { runCatching { AccuracyProfile.valueOf(it) }.getOrNull() }
                    ?: AccuracyProfile.STANDARD
            }
            .first()

    override suspend fun setAccuracyProfile(profile: AccuracyProfile) {
        context.dataStore.edit { prefs ->
            prefs[ACCURACY_PROFILE_KEY] = profile.name
        }
    }

    // ── Gallery Scan Interval ─────────────────────────────────────────────────

    override suspend fun getScanInterval(): ScanInterval =
        context.dataStore.data
            .map { prefs ->
                prefs[SCAN_INTERVAL_KEY]
                    ?.let { runCatching { ScanInterval.valueOf(it) }.getOrNull() }
                    ?: ScanInterval.STANDARD
            }
            .first()

    override suspend fun setScanInterval(interval: ScanInterval) {
        context.dataStore.edit { prefs ->
            prefs[SCAN_INTERVAL_KEY] = interval.name
        }
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    companion object {
        private val ONBOARDING_COMPLETE_KEY  = booleanPreferencesKey("onboarding_complete")
        private val LANGUAGE_KEY             = stringPreferencesKey("language")
        private val ACCURACY_PROFILE_KEY     = stringPreferencesKey("accuracy_profile")
        private val SCAN_INTERVAL_KEY        = stringPreferencesKey("scan_interval")
    }
}
