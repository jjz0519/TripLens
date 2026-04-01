package com.cooldog.triplens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Top-level delegate — one DataStore per named file per process.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "triplens_prefs")

/**
 * Interface over persisted app preferences.
 *
 * Extracted as an interface so ViewModel tests can inject a fake without touching real DataStore
 * (which requires an Android context). Task 16 (Settings) will add more keys here.
 */
interface AppPreferences {
    /** Returns true if first-launch permission onboarding has been completed. Default: false. */
    suspend fun isOnboardingComplete(): Boolean

    /** Marks onboarding as complete. Idempotent — safe to call multiple times. */
    suspend fun setOnboardingComplete()
}

/**
 * Production implementation backed by AndroidX DataStore Preferences.
 *
 * Registered as a singleton in [com.cooldog.triplens.di.AndroidModule]: DataStore must not be
 * opened more than once per file per process, so a singleton is required.
 */
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

    companion object {
        private val ONBOARDING_COMPLETE_KEY = booleanPreferencesKey("onboarding_complete")
    }
}
