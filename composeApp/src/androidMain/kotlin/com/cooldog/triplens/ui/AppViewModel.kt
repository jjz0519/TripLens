package com.cooldog.triplens.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.ui.theme.Palette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolves the app's start destination once on launch, and silently resumes any active session.
 *
 * ## Start destination priority
 * 1. [StartDestination.Onboarding] — if [isOnboardingCompleteFn] returns false (first launch)
 * 2. [StartDestination.Recording]  — if an active recording session exists (service running OR not)
 * 3. [StartDestination.TripList]   — default (no active session)
 *
 * ## Session auto-resume
 * A session ends only when the user explicitly taps Stop. If [getActiveSessionFn] returns a
 * session and the service is already live (START_STICKY recovery), we route straight to Recording.
 * If the service has also died (force-stop or long-dead process), we restart it against the same
 * [Session.id] — no new session is created, track points already written stay attached, and the
 * user never sees a dialog.
 *
 * ## Why lambdas, not direct dependencies?
 * All injectable behavior is expressed as lambdas so unit tests supply pure functions without
 * constructing a real database, DataStore, or Android service. See [AppViewModelTest].
 *
 * ## Why injectable [ioDispatcher]?
 * All lambdas perform IO. Accepting [ioDispatcher] lets tests pass a test dispatcher so
 * `advanceUntilIdle()` controls all coroutines deterministically.
 */
class AppViewModel(
    private val getActiveSessionFn:      () -> Session?,
    private val isOnboardingCompleteFn:  suspend () -> Boolean = { true },
    /** Returns true if [LocationTrackingService] is currently running or recovering. */
    private val isServiceRunningFn:      () -> Boolean = { false },
    /**
     * Restarts [LocationTrackingService] against the existing [Session.id] so that GPS tracking
     * resumes on the same DB row. Called when the DB has a recording session but the service has
     * died. Does NOT mark the session interrupted or create a new one.
     */
    private val resumeOrphanedSessionFn: suspend (session: Session) -> Unit = {},
    private val getPaletteFn:            suspend () -> Palette = { Palette.MOSS },
    private val ioDispatcher:            CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface StartDestination {
        data object Loading    : StartDestination
        data object Onboarding : StartDestination
        data object TripList   : StartDestination
        data object Recording  : StartDestination
    }

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    /**
     * True while a recording session is active. Initialized from the startup DB check.
     * Updated at runtime via [onSessionActiveChanged] when RecordingViewModel starts/stops.
     */
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _palette = MutableStateFlow(Palette.MOSS)
    val palette: StateFlow<Palette> = _palette.asStateFlow()

    /** Called by RecordingScreen when a session starts or stops to update the bottom-nav pulse. */
    fun onSessionActiveChanged(isActive: Boolean) {
        Log.d(TAG, "onSessionActiveChanged: isActive=$isActive")
        _isSessionActive.value = isActive
    }

    init {
        Log.d(TAG, "init: resolving start destination")
        viewModelScope.launch {
            withContext(ioDispatcher) {
                try {
                    if (!isOnboardingCompleteFn()) {
                        Log.i(TAG, "init: onboarding not complete → Onboarding")
                        _startDestination.value = StartDestination.Onboarding
                        return@withContext
                    }

                    val active = getActiveSessionFn()

                    when {
                        active == null -> {
                            Log.i(TAG, "init: no active session → TripList")
                            _startDestination.value = StartDestination.TripList
                        }
                        isServiceRunningFn() -> {
                            // Service is live (or mid-START_STICKY recovery) — route straight to Recording.
                            Log.i(TAG, "init: active session + service running → Recording (sessionId=${active.id})")
                            _isSessionActive.value = true
                            _startDestination.value = StartDestination.Recording
                        }
                        else -> {
                            // Service is gone but session is still recording in the DB.
                            // Restart the service against the same sessionId — no new row, no dialog.
                            Log.i(TAG, "init: orphaned session (id=${active.id}) — restarting service silently")
                            try {
                                resumeOrphanedSessionFn(active)
                                Log.i(TAG, "init: service restarted for session=${active.id} → Recording")
                            } catch (e: Exception) {
                                // Service start failed (e.g. Android killed us immediately again).
                                // Route to Recording anyway; RecordingViewModel will rehydrate from DB
                                // and the user can tap Stop if GPS is not collecting.
                                Log.e(TAG, "init: failed to restart service for orphaned session", e)
                            }
                            _isSessionActive.value = true
                            _startDestination.value = StartDestination.Recording
                        }
                    }
                    try {
                        _palette.value = getPaletteFn()
                        Log.d(TAG, "init: palette loaded → ${_palette.value}")
                    } catch (e: Exception) {
                        Log.w(TAG, "init: failed to load palette, defaulting to MOSS", e)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve start destination", e)
                    _startDestination.value = StartDestination.TripList
                }
            }
        }
    }

    companion object {
        private const val TAG = "TripLens/AppViewModel"
    }
}
