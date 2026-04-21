package com.cooldog.triplens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.ui.theme.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "triplens_prefs")

interface AppPreferences {

    suspend fun isOnboardingComplete(): Boolean
    suspend fun setOnboardingComplete()
    suspend fun getLanguage(): Language
    suspend fun setLanguage(language: Language)
    suspend fun getAccuracyProfile(): AccuracyProfile
    suspend fun setAccuracyProfile(profile: AccuracyProfile)
    suspend fun getScanInterval(): ScanInterval
    suspend fun setScanInterval(interval: ScanInterval)

    // ── Palette (Task 20) ─────────────────────────────────────────────────────
    suspend fun getPalette(): Palette
    suspend fun setPalette(palette: Palette)
}

class DataStoreAppPreferences(private val context: Context) : AppPreferences {

    override suspend fun isOnboardingComplete(): Boolean =
        context.dataStore.data
            .map { prefs -> prefs[ONBOARDING_COMPLETE_KEY] ?: false }
            .first()

    override suspend fun setOnboardingComplete() {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETE_KEY] = true
        }
    }

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

    override suspend fun getPalette(): Palette =
        context.dataStore.data
            .map { prefs ->
                prefs[PALETTE_KEY]
                    ?.let { runCatching { Palette.valueOf(it) }.getOrNull() }
                    ?: Palette.MOSS
            }
            .first()

    override suspend fun setPalette(palette: Palette) {
        context.dataStore.edit { prefs ->
            prefs[PALETTE_KEY] = palette.name
        }
    }

    companion object {
        private val ONBOARDING_COMPLETE_KEY  = booleanPreferencesKey("onboarding_complete")
        private val LANGUAGE_KEY             = stringPreferencesKey("language")
        private val ACCURACY_PROFILE_KEY     = stringPreferencesKey("accuracy_profile")
        private val SCAN_INTERVAL_KEY        = stringPreferencesKey("scan_interval")
        private val PALETTE_KEY              = stringPreferencesKey("palette")
    }
}
