package com.cooldog.triplens.di

import android.content.Intent
import androidx.core.content.ContextCompat
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.db.DatabaseDriverFactory
import com.cooldog.triplens.data.AppPreferences
import com.cooldog.triplens.data.DataStoreAppPreferences
import com.cooldog.triplens.export.AndroidFileSystem
import com.cooldog.triplens.export.PlatformFileSystem
import com.cooldog.triplens.platform.AndroidAudioRecorder
import com.cooldog.triplens.platform.AndroidGalleryScanner
import com.cooldog.triplens.platform.AudioRecorder
import com.cooldog.triplens.platform.GalleryScanner
import com.cooldog.triplens.platform.LocationProvider
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TripRepository
import com.cooldog.triplens.service.LocationTrackingService
import com.cooldog.triplens.ui.AppViewModel
import com.cooldog.triplens.ui.onboarding.OnboardingViewModel
import com.cooldog.triplens.ui.recording.RecordingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for Android platform-specific bindings.
 *
 * ## What belongs here
 * Everything that requires [android.content.Context]: the database driver, the database itself,
 * location, gallery, audio, and file system services.
 *
 * ## Database construction chain
 * Context  →  DatabaseDriverFactory(context)  →  .createDriver(): SqlDriver  →  AppDatabase(driver)
 * AppDatabase is a singleton: all repositories in SharedModule share the same instance,
 * which ensures FK constraints work correctly across repositories in the same in-memory session.
 *
 * ## GalleryScanner and AudioRecorder — factory (not single)
 * Both are stateful and designed to be used for one recording session at a time:
 * - AndroidGalleryScanner holds `lastScanTimestamp` which must start at 0 for each new session.
 * - AndroidAudioRecorder holds `isRecording` state and a live MediaRecorder reference.
 * Using `factory {}` ensures a fresh instance is created for each new recording session.
 * They are never held as long-lived singletons.
 *
 * ## LocationProvider — single
 * LocationProvider wraps FusedLocationProviderClient which is internally managed by Google
 * Play Services. There is no correctness issue with sharing it as a singleton; the service
 * controls start/stop explicitly.
 */
val androidModule = module {

    // DatabaseDriverFactory requires Context. AndroidSqliteDriver handles WAL/FK setup.
    single { DatabaseDriverFactory(androidContext()) }

    // AppDatabase is the single source of truth for all repositories. Singleton ensures
    // all 5 repositories see the same SQLite connection and the same in-memory state.
    single { AppDatabase(get<DatabaseDriverFactory>().createDriver()) }

    // Location provider — singleton since FusedLocationProviderClient is a heavy object.
    single { LocationProvider(androidContext()) }

    // GalleryScanner — factory: stateful per-session, must start fresh each recording.
    factory<GalleryScanner> { AndroidGalleryScanner(androidContext()) }

    // AudioRecorder — factory: stateful per-session, must start fresh each recording.
    factory<AudioRecorder> { AndroidAudioRecorder(androidContext()) }

    // File system — singleton: stateless, no session-specific state.
    single<PlatformFileSystem> { AndroidFileSystem(androidContext()) }

    // AppPreferences — DataStore wrapper. Must be a singleton: DataStore must not be opened twice.
    single<AppPreferences> { DataStoreAppPreferences(androidContext()) }

    // AppViewModel — resolves the start destination by querying SessionRepository at launch.
    // viewModel {} registers a Koin ViewModel factory; the same instance is returned for the
    // same ViewModelStore (i.e., the same Activity), so App() and AppNavGraph() share one VM.
    viewModel {
        AppViewModel(
            getActiveSessionFn = { get<SessionRepository>().getActiveSession() },
            isOnboardingCompleteFn = { get<AppPreferences>().isOnboardingComplete() },
        )
    }

    // OnboardingViewModel — first-launch permission walkthrough.
    viewModel {
        OnboardingViewModel(appPreferences = get())
    }

    // RecordingViewModel — idle state machine for the Recording screen.
    // startService lambda uses ContextCompat.startForegroundService to ensure correct behaviour
    // on API 26+ where startService() alone does not allow foreground promotion.
    viewModel {
        val ctx = androidContext()
        RecordingViewModel(
            createGroupFn = { id, name, now ->
                get<TripRepository>().createGroup(id, name, now)
            },
            createSessionFn = { id, groupId, name, startTime ->
                get<SessionRepository>().createSession(id, groupId, name, startTime)
            },
            startService = { sessionId, profile ->
                val intent = Intent(ctx, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_START
                    putExtra(LocationTrackingService.EXTRA_SESSION_ID, sessionId)
                    putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, profile.name)
                }
                ContextCompat.startForegroundService(ctx, intent)
            },
        )
    }
}
