package com.cooldog.triplens.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.model.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolves the app's start destination once on launch, and handles orphaned session recovery.
 *
 * ## Start destination priority
 * 1. [StartDestination.Onboarding] — if [isOnboardingCompleteFn] returns false (first launch)
 * 2. [StartDestination.Recording]  — if an active recording session exists AND the service is running
 * 3. [StartDestination.TripList]   — default (also used when an orphaned session is found)
 *
 * ## Orphaned session recovery
 * If [getActiveSessionFn] returns a session but [isServiceRunningFn] returns false, the session
 * was left in "recording" state without a live foreground service — typically caused by the OS
 * killing both the app process and the service before either could write "completed". In this
 * case [recoverySession] is set to the orphaned session and a [SessionRecoveryDialog] is shown
 * in [App]. The user can Resume (start a fresh session in the same group) or Discard (mark
 * the session as interrupted).
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
    private val getActiveSessionFn:        () -> Session?,
    private val isOnboardingCompleteFn:    suspend () -> Boolean = { true },
    /** Returns true if [LocationTrackingService] is currently running or recovering. */
    private val isServiceRunningFn:        () -> Boolean = { false },
    /**
     * Marks the orphaned session as interrupted, creates a new session in the same TripGroup,
     * and starts [LocationTrackingService] with the new session. Called on Resume.
     */
    private val resumeOrphanedSessionFn:   suspend (orphanedSessionId: String, groupId: String) -> Unit = { _, _ -> },
    /**
     * Marks the orphaned session as interrupted without starting a new one. Called on Discard.
     */
    private val discardOrphanedSessionFn:  (orphanedSessionId: String) -> Unit = {},
    private val ioDispatcher:              CoroutineDispatcher = Dispatchers.IO,
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
     * Non-null while the recovery dialog is visible. Carries the orphaned session so
     * [onRecoveryResume] can pass its id and groupId to [resumeOrphanedSessionFn] without
     * requiring additional state. Cleared to null once the user makes a choice.
     */
    private val _recoverySession = MutableStateFlow<Session?>(null)
    val recoverySession: StateFlow<Session?> = _recoverySession.asStateFlow()

    /**
     * True while a recording session is active. Initialized from the startup DB check.
     * Updated at runtime via [onSessionActiveChanged] when RecordingViewModel starts/stops.
     */
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    /** Called by RecordingScreen when a session starts or stops to update the bottom-nav pulse. */
    fun onSessionActiveChanged(isActive: Boolean) {
        Log.d(TAG, "onSessionActiveChanged: isActive=$isActive")
        _isSessionActive.value = isActive
    }

    /**
     * Called when the user taps "Resume" in [SessionRecoveryDialog].
     *
     * Marks the orphaned session as interrupted, creates a fresh session in the same TripGroup,
     * starts LocationTrackingService, then navigates to the Recording screen.
     */
    fun onRecoveryResume() {
        val session = _recoverySession.value ?: return
        Log.i(TAG, "onRecoveryResume: resuming from orphaned session=${session.id} group=${session.groupId}")
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    resumeOrphanedSessionFn(session.id, session.groupId)
                }
                // Clear dialog only after the IO work succeeds. If resumeOrphanedSessionFn
                // throws (e.g. service start failure), _recoverySession stays non-null so the
                // dialog remains visible and the user can retry or discard — rather than the
                // orphaned session silently re-appearing on every subsequent cold start.
                _recoverySession.value = null
                _isSessionActive.value = true
                _startDestination.value = StartDestination.Recording
                Log.i(TAG, "onRecoveryResume: service started, navigating to Recording")
            } catch (e: Exception) {
                // Recovery failed (DB write or service start error). Fall back to TripList.
                // _recoverySession is NOT cleared so the dialog re-shows on the next recomposition,
                // giving the user a chance to retry Resume or choose Discard instead.
                Log.e(TAG, "onRecoveryResume: recovery failed — leaving dialog visible for retry", e)
                _startDestination.value = StartDestination.TripList
            }
        }
    }

    /**
     * Called when the user taps "Discard" in [SessionRecoveryDialog] (or taps outside it).
     *
     * Marks the orphaned session as interrupted. The start destination remains TripList
     * (already set when the orphan was detected).
     */
    fun onRecoveryDiscard() {
        val session = _recoverySession.value ?: return
        Log.i(TAG, "onRecoveryDiscard: discarding orphaned session=${session.id}")
        _recoverySession.value = null
        viewModelScope.launch {
            withContext(ioDispatcher) {
                try {
                    discardOrphanedSessionFn(session.id)
                } catch (e: Exception) {
                    // Non-fatal: even if the DB write fails, the user has dismissed the dialog.
                    // The session stays "recording" in the DB, which is harmless — it will be
                    // detected again next launch and the dialog shown again.
                    Log.e(TAG, "onRecoveryDiscard: failed to mark session as interrupted", e)
                }
            }
        }
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
                            // No session in the DB — clean state.
                            Log.i(TAG, "init: no active session → TripList")
                            _startDestination.value = StartDestination.TripList
                        }
                        isServiceRunningFn() -> {
                            // Session exists AND service is live (or recovering via START_STICKY).
                            Log.i(TAG, "init: active session + service running → Recording (sessionId=${active.id})")
                            _isSessionActive.value = true
                            _startDestination.value = StartDestination.Recording
                        }
                        else -> {
                            // Session exists but NO service: orphaned. Show recovery dialog.
                            // Start destination is TripList so the user has a sensible screen
                            // behind the dialog; it switches to Recording if they choose Resume.
                            Log.w(TAG, "init: orphaned session detected (sessionId=${active.id}) — showing recovery dialog")
                            _recoverySession.value = active
                            _startDestination.value = StartDestination.TripList
                        }
                    }
                } catch (e: Exception) {
                    // DB or DataStore errors are non-fatal; default to TripList.
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
