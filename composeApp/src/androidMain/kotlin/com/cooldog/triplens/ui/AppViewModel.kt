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
 * Resolves the app's start destination once on launch by checking for an active recording session.
 *
 * ## Why a lambda, not SessionRepository directly?
 * [getActiveSessionFn] is a lambda so tests supply a pure function and avoid constructing a real
 * [com.cooldog.triplens.repository.SessionRepository] with a database. The lambda maps 1:1 to
 * [com.cooldog.triplens.repository.SessionRepository.getActiveSession] in production.
 *
 * ## Why an injectable [ioDispatcher]?
 * [getActiveSessionFn] is synchronous but must not run on the main thread (it queries SQLite).
 * Accepting [ioDispatcher] as a parameter lets tests pass the test dispatcher so
 * `advanceUntilIdle()` controls all coroutines deterministically without real IO thread races.
 */
class AppViewModel(
    private val getActiveSessionFn: () -> Session?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface StartDestination {
        data object Loading : StartDestination
        data object TripList : StartDestination
        data object Recording : StartDestination
    }

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    /**
     * True while a recording session is active.
     *
     * Initialized from the startup DB check. Kept separate from [startDestination] so it can be
     * updated independently at any time via [onSessionActiveChanged] — e.g., when RecordingViewModel
     * (Task 12/13) starts or stops a session after the app is already running.
     *
     * Using [startDestination] directly as the source of truth for "is recording" would mean this
     * state could never change after the initial startup resolution, since [_startDestination] is
     * never written to again after init.
     */
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    /**
     * Called by RecordingViewModel (Task 12/13) when a session is started or stopped from the UI.
     * Updates the pulsing animation on the bottom-nav Record tab.
     */
    fun onSessionActiveChanged(isActive: Boolean) {
        Log.d(TAG, "onSessionActiveChanged: isActive=$isActive")
        _isSessionActive.value = isActive
    }

    init {
        Log.d(TAG, "init: querying active session on startup")
        viewModelScope.launch {
            val active = withContext(ioDispatcher) {
                try {
                    getActiveSessionFn()
                } catch (e: Exception) {
                    // DB errors (e.g., corruption) are non-fatal; default to TripList.
                    Log.e(TAG, "Failed to query active session at startup", e)
                    null
                }
            }
            val isActive = active != null
            _isSessionActive.value = isActive
            val resolved = if (isActive) StartDestination.Recording else StartDestination.TripList
            Log.i(TAG, "init: resolved start destination to $resolved (sessionId=${active?.id})")
            _startDestination.value = resolved
        }
    }

    companion object {
        private const val TAG = "TripLens/AppViewModel"
    }
}
