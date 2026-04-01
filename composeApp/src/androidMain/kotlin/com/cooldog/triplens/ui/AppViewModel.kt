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
 * Resolves the app's start destination once on launch.
 *
 * ## Start destination priority
 * 1. [StartDestination.Onboarding] — if [isOnboardingCompleteFn] returns false (first launch)
 * 2. [StartDestination.Recording] — if an active recording session exists (killed mid-recording)
 * 3. [StartDestination.TripList] — default
 *
 * ## Why lambdas, not direct dependencies?
 * Both [getActiveSessionFn] and [isOnboardingCompleteFn] are lambdas so unit tests supply
 * pure functions without constructing a real [com.cooldog.triplens.repository.SessionRepository]
 * or DataStore. The lambda maps 1:1 to the real call sites in [com.cooldog.triplens.di.AndroidModule].
 *
 * ## Why injectable [ioDispatcher]?
 * Both functions perform IO (SQLite and DataStore). Accepting [ioDispatcher] lets tests pass a
 * test dispatcher so `advanceUntilIdle()` controls all coroutines deterministically.
 */
class AppViewModel(
    private val getActiveSessionFn: () -> Session?,
    private val isOnboardingCompleteFn: suspend () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface StartDestination {
        data object Loading : StartDestination
        data object Onboarding : StartDestination
        data object TripList : StartDestination
        data object Recording : StartDestination
    }

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

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
                    val isActive = active != null
                    _isSessionActive.value = isActive
                    val resolved = if (isActive) StartDestination.Recording else StartDestination.TripList
                    Log.i(TAG, "init: resolved to $resolved (sessionId=${active?.id})")
                    _startDestination.value = resolved
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
