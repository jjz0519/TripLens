package com.cooldog.triplens.ui.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.data.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the first-launch permission onboarding screen.
 *
 * ## Responsibilities
 * 1. Expose [UiState.ShowPermissions] — the only UI state (the screen is shown exactly once).
 * 2. After permissions are handled (any outcome), write [AppPreferences.setOnboardingComplete]
 *    and emit [Event.NavigateToTripList] so [OnboardingScreen] navigates away.
 *
 * ## Why no permission logic here?
 * Permission launchers (ActivityResultContracts) are lifecycle-aware and must be registered in
 * a Composable. [OnboardingScreen] owns the launchers and calls [onPermissionsHandled] when done.
 */
class OnboardingViewModel(
    private val appPreferences: AppPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface UiState {
        data object ShowPermissions : UiState
    }

    sealed interface Event {
        /** All permissions handled (any outcome). Navigate away from onboarding. */
        data object NavigateToTripList : Event
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.ShowPermissions)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Channel-backed flow: each event is delivered exactly once even if the collector is slow.
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * Called by [OnboardingScreen] after the full permission sequence finishes (granted or
     * denied — optional permissions do not block completion).
     */
    fun onPermissionsHandled() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                appPreferences.setOnboardingComplete()
            }
            Log.i(TAG, "onPermissionsHandled: onboarding complete")
            _events.send(Event.NavigateToTripList)
        }
    }

    companion object {
        private const val TAG = "TripLens/OnboardingVM"
    }
}
