package com.cooldog.triplens.di

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.cooldog.triplens.R
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.cooldog.triplens.data.AppPreferences
import com.cooldog.triplens.data.DataStoreAppPreferences
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.db.DatabaseDriverFactory
import com.cooldog.triplens.export.AndroidFileSystem
import com.cooldog.triplens.export.ExportUseCase
import com.cooldog.triplens.export.PlatformFileSystem
import com.cooldog.triplens.platform.AndroidAudioRecorder
import com.cooldog.triplens.platform.AndroidGalleryScanner
import com.cooldog.triplens.platform.AudioRecorder
import com.cooldog.triplens.platform.GalleryScanner
import com.cooldog.triplens.platform.LocationProvider
import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TrackPointRepository
import com.cooldog.triplens.repository.TripRepository
import com.cooldog.triplens.service.LocationTrackingService
import com.cooldog.triplens.ui.AppViewModel
import com.cooldog.triplens.ui.onboarding.OnboardingViewModel
import com.cooldog.triplens.ui.recording.RecordingDeps
import com.cooldog.triplens.ui.recording.RecordingViewModel
import com.cooldog.triplens.ui.sessionreview.SessionReviewDeps
import com.cooldog.triplens.ui.sessionreview.SessionReviewViewModel
import com.cooldog.triplens.ui.settings.SettingsViewModel
import com.cooldog.triplens.ui.tripdetail.TripDetailDeps
import com.cooldog.triplens.ui.tripdetail.TripDetailViewModel
import com.cooldog.triplens.ui.triplist.TripListDeps
import com.cooldog.triplens.ui.triplist.TripListViewModel
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
private const val TAG = "TripLens/AndroidModule"

val androidModule = module {

    // DatabaseDriverFactory requires Context. AndroidSqliteDriver handles WAL/FK setup.
    single { DatabaseDriverFactory(androidContext()) }

    // AppDatabase is the single source of truth for all repositories. Singleton ensures
    // all 5 repositories see the same SQLite connection and the same in-memory state.
    //
    // Startup integrity check (Task 18): if SQLite reports a corrupted database, close the
    // driver, rename the file to triplens.db.corrupt, and open a fresh database. This is a
    // last-resort recovery — data loss is unavoidable, but the app remains usable.
    single {
        val factory = get<DatabaseDriverFactory>()
        var driver  = factory.createDriver()
        var db      = AppDatabase(driver)
        if (!db.integrityCheck()) {
            Log.e(TAG, "DB integrity check FAILED — renaming corrupt database and creating a fresh one")
            driver.close()
            factory.renameCorruptDb()
            driver = factory.createDriver()
            db     = AppDatabase(driver)
            Log.i(TAG, "Fresh database created after corrupt recovery")
        }
        db
    }

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

    // AppViewModel — resolves the start destination by querying SessionRepository at launch,
    // and handles orphaned session recovery (Task 18).
    // viewModel {} registers a Koin ViewModel factory; the same instance is returned for the
    // same ViewModelStore (i.e., the same Activity), so App() and AppNavGraph() share one VM.
    viewModel {
        val ctx = androidContext()
        AppViewModel(
            getActiveSessionFn     = { get<SessionRepository>().getActiveSession() },
            isOnboardingCompleteFn = { get<AppPreferences>().isOnboardingComplete() },

            // Service is considered running if the in-process instance reference is set
            // OR if SharedPreferences still contains the session ID (START_STICKY may be
            // in the middle of restarting the service — the prefs key is written before
            // onDestroy clears it, so its presence means the service was alive recently).
            isServiceRunningFn = {
                LocationTrackingService.runningInstance != null ||
                ctx.getSharedPreferences(LocationTrackingService.PREFS_NAME, Context.MODE_PRIVATE)
                    .contains(LocationTrackingService.PREFS_SESSION_ID)
            },

            // Resume: mark the orphaned session interrupted, create a new session in the
            // same group, and start LocationTrackingService with the new session's ID.
            resumeOrphanedSessionFn = { orphanedSessionId, groupId ->
                get<SessionRepository>().markInterrupted(orphanedSessionId)
                val newId   = java.util.UUID.randomUUID().toString()
                val now     = System.currentTimeMillis()
                val name    = ctx.getString(R.string.session_default_name)
                get<SessionRepository>().createSession(newId, groupId, name, now)
                val profile = get<AppPreferences>().getAccuracyProfile()
                ContextCompat.startForegroundService(ctx,
                    Intent(ctx, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_START
                        putExtra(LocationTrackingService.EXTRA_SESSION_ID, newId)
                        putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, profile.name)
                        putExtra(LocationTrackingService.EXTRA_SESSION_START_TIME, now)
                    }
                )
            },

            // Discard: mark the orphaned session interrupted; no new session is created.
            discardOrphanedSessionFn = { orphanedSessionId ->
                get<SessionRepository>().markInterrupted(orphanedSessionId)
            },
        )
    }

    // OnboardingViewModel — first-launch permission walkthrough.
    viewModel {
        OnboardingViewModel(appPreferences = get())
    }

    // RecordingViewModel — full recording state machine (idle → active → review).
    //
    // All external calls are bundled in RecordingDeps to prevent accidental lambda swap bugs:
    // several lambdas share identical functional types and would be undetectable by position alone.
    //
    // audioRecorder is resolved via get<AudioRecorder>() which maps to the factory binding above,
    // so each ViewModel instance gets a fresh recorder with clean state.
    viewModel {
        val ctx = androidContext()
        RecordingViewModel(
            deps = RecordingDeps(
                // ── Idle → Active transition ──────────────────────────────────────────
                createGroupFn = { id, name, now ->
                    get<TripRepository>().createGroup(id, name, now)
                },
                createSessionFn = { id, groupId, name, startTime ->
                    get<SessionRepository>().createSession(id, groupId, name, startTime)
                },
                // Read the saved accuracy profile from DataStore so the user's Settings
                // choice is honoured at session start (Task 16).
                getAccuracyProfileFn = {
                    get<AppPreferences>().getAccuracyProfile()
                },
                // ContextCompat.startForegroundService is required on API 26+: plain
                // startService() cannot promote a service to foreground on those versions.
                startService = { sessionId, profile, sessionStartTime ->
                    val intent = Intent(ctx, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_START
                        putExtra(LocationTrackingService.EXTRA_SESSION_ID, sessionId)
                        putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, profile.name)
                        putExtra(LocationTrackingService.EXTRA_SESSION_START_TIME, sessionStartTime)
                    }
                    ContextCompat.startForegroundService(ctx, intent)
                },

                // ── Active state — data fetching (polled every 5 s) ──────────────────
                getTrackPointsFn = { sessionId ->
                    get<TrackPointRepository>().getBySession(sessionId)
                },
                getMediaRefsFn = { sessionId ->
                    get<MediaRefRepository>().getBySession(sessionId)
                },
                getNotesFn = { sessionId ->
                    get<NoteRepository>().getBySession(sessionId)
                },

                // ── Active state — writes ────────────────────────────────────────────
                createTextNoteFn = { id, sessionId, content, createdAt, lat, lng ->
                    get<NoteRepository>().createTextNote(id, sessionId, content, createdAt, lat, lng)
                },
                createVoiceNoteFn = { id, sessionId, audioFilename, durationSeconds, createdAt, lat, lng ->
                    get<NoteRepository>().createVoiceNote(
                        id, sessionId, audioFilename, durationSeconds, createdAt, lat, lng,
                    )
                },
                completeSessionFn = { id, endTime ->
                    get<SessionRepository>().completeSession(id, endTime)
                },
                // Persist the running haversine distance on every poll cycle (~3s) during
                // recording. Survives silent app kills — see RecordingViewModel.refreshActiveData.
                setDistanceFn = { id, distanceMeters ->
                    get<SessionRepository>().setDistance(id, distanceMeters)
                },
                // ACTION_STOP is handled in LocationTrackingService.onStartCommand, which then
                // calls stopSelf(). We send via startService (not startForegroundService) because
                // the service already exists; we are just delivering a command, not starting it.
                stopServiceFn = {
                    val intent = Intent(ctx, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_STOP
                    }
                    ctx.startService(intent)
                },

                // ── Platform ─────────────────────────────────────────────────────────
                audioRecorder = get(),
            )
        )
    }

    // ── TripListViewModel ─────────────────────────────────────────────────────
    // Loads all groups with aggregate stats and exposes rename/delete/export actions.
    //
    // Locale note: user-facing label strings (noSessionsLabel, etc.) are resolved once
    // at ViewModel construction time via androidContext(). This is correct because Koin's
    // viewModel {} factory re-runs after every Activity recreation — and AppCompatDelegate
    // applies the per-app locale to the Application context before the Activity recreates,
    // so the new locale is always in effect by the time this factory is called again.
    viewModel {
        val ctx = androidContext()
        TripListViewModel(
            deps = TripListDeps(
                getAllGroupsWithStatsFn = { get<TripRepository>().getAllGroupsWithStats() },
                getTrackPointsByGroupFn = { groupId ->
                    val sessions = get<SessionRepository>().getSessionsByGroup(groupId)
                    sessions.flatMap { get<TrackPointRepository>().getBySession(it.id) }
                },
                deleteGroupFn = { id -> get<TripRepository>().deleteGroup(id) },
                renameGroupFn = { id, name, now ->
                    get<TripRepository>().renameGroup(id, name, now)
                },
                exportFn = { groupId, nowMs ->
                    get<ExportUseCase>().export(groupId, nowMs)
                },
                noSessionsLabel = ctx.getString(R.string.no_sessions),
            )
        )
    }

    // ── SessionReviewViewModel ────────────────────────────────────────────────
    // Receives sessionId via Koin's parametersOf().
    viewModel { params ->
        val sessionId: String = params.get()
        val ctx = androidContext()
        SessionReviewViewModel(
            deps = SessionReviewDeps(
                sessionId = sessionId,
                getSessionFn = { get<SessionRepository>().getSessionById(it) },
                getTrackPointsFn = { get<TrackPointRepository>().getBySession(it) },
                getMediaRefsFn = { get<MediaRefRepository>().getBySession(it) },
                getNotesFn = { get<NoteRepository>().getBySession(it) },
                // Voice note files are stored in {filesDir}/notes/{filename}.
                // Resolved here to keep Context out of the ViewModel.
                getAudioFilePathFn = { filename ->
                    ctx.filesDir.resolve("notes/$filename").absolutePath
                },
                sessionNotFoundMessage = ctx.getString(R.string.session_not_found),
            )
        )
    }

    // ── TripDetailViewModel ───────────────────────────────────────────────────
    // Receives groupId via Koin's parametersOf() — cross-platform KMP API.
    viewModel { params ->
        val groupId: String = params.get()
        val ctx = androidContext()
        TripDetailViewModel(
            deps = TripDetailDeps(
                groupId = groupId,
                getGroupByIdFn = { get<TripRepository>().getGroupById(it) },
                getSessionsByGroupFn = { get<SessionRepository>().getSessionsByGroup(it) },
                getTrackPointsFn = { get<TrackPointRepository>().getBySession(it) },
                renameSessionFn = { id, name ->
                    get<SessionRepository>().renameSession(id, name)
                },
                exportFn = { gId, nowMs ->
                    get<ExportUseCase>().export(gId, nowMs)
                },
                sessionInProgressLabel = ctx.getString(R.string.session_in_progress),
                tripNotFoundMessage    = ctx.getString(R.string.trip_not_found),
            )
        )
    }

    // ── SettingsViewModel ─────────────────────────────────────────────────────
    // Reads/writes DataStore preferences and notifies active service on profile change.
    viewModel {
        val ctx = androidContext()
        SettingsViewModel(
            appPreferences   = get(),
            isSessionActiveFn = { get<SessionRepository>().getActiveSession() != null },
            // Send ACTION_UPDATE_PROFILE so the running service shifts its GPS interval
            // immediately without waiting for the next session start.
            notifyServiceFn  = { profile ->
                val intent = Intent(ctx, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_UPDATE_PROFILE
                    putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, profile.name)
                }
                ctx.startService(intent)
            },
            // Build the LocaleListCompat and call AppCompatDelegate on the main thread.
            // SYSTEM → empty locale list (OS default); others → the BCP 47 tag.
            applyLocaleFn = { language ->
                val localeList = if (language.bcp47Tag == null) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(language.bcp47Tag)
                }
                AppCompatDelegate.setApplicationLocales(localeList)
            },
        )
    }
}
