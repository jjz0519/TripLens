package com.cooldog.triplens.ui.recording

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.platform.AccuracyProfile
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * ViewModel for the Recording screen — drives the idle state machine.
 *
 * On "Start Recording" tap, auto-creates a [TripGroup] (name = today's UTC date) and a [Session]
 * (name = "Session 1"), then fires [LocationTrackingService] via [startService]. No group-selector
 * dialog is shown; the user renames the group after stopping the recording (Task 13).
 *
 * ## Design: lambdas instead of direct repository injection
 * [createGroupFn], [createSessionFn], and [startService] are lambdas so unit tests supply pure
 * functions without a real database or Android context. The DI module wires these to the real
 * repositories and service at runtime.
 *
 * ## Guard against double-tap
 * Once [UiState.StartingSession] is set, further [onStartTapped] calls are no-ops — preventing
 * duplicate TripGroups and Sessions if the user taps rapidly.
 */
class RecordingViewModel(
    private val createGroupFn: (id: String, name: String, now: Long) -> Unit,
    private val createSessionFn: (id: String, groupId: String, name: String, startTime: Long) -> Unit,
    private val startService: (sessionId: String, profile: AccuracyProfile) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface UiState {
        /** "Start Recording" button visible and enabled. */
        data object Idle : UiState

        /** Creating TripGroup + Session + starting service. Button shows loading indicator. */
        data object StartingSession : UiState
    }

    sealed interface Event {
        /** Session created and service started. RecordingScreen notifies AppViewModel and Task 13 handles the UI transition. */
        data object NavigateToActiveRecording : Event

        /** Location permission revoked post-onboarding. Show [PermissionRationaleDialog]. */
        data object ShowPermissionRationale : Event
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * Called when the user taps "Start Recording".
     *
     * @param locationGranted True if [android.Manifest.permission.ACCESS_FINE_LOCATION] is
     *   currently granted. If false, emits [Event.ShowPermissionRationale] and returns without
     *   starting a session.
     */
    fun onStartTapped(locationGranted: Boolean) {
        if (!locationGranted) {
            Log.w(TAG, "onStartTapped: location not granted — emitting ShowPermissionRationale")
            viewModelScope.launch { _events.send(Event.ShowPermissionRationale) }
            return
        }
        if (_uiState.value != UiState.Idle) {
            Log.d(TAG, "onStartTapped: ignored — state is ${_uiState.value}")
            return
        }
        _uiState.value = UiState.StartingSession
        Log.i(TAG, "onStartTapped: auto-creating TripGroup + Session")

        viewModelScope.launch {
            withContext(ioDispatcher) {
                val now = clock()
                val groupId = UUID.randomUUID().toString()
                val sessionId = UUID.randomUUID().toString()

                // Format today's date in UTC. Locale.US ensures consistent yyyy-MM-dd output
                // regardless of device locale (some locales use different separators).
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(now))

                createGroupFn(groupId, dateStr, now)
                Log.d(TAG, "Created TripGroup id=$groupId name=$dateStr")

                createSessionFn(sessionId, groupId, "Session 1", now)
                Log.d(TAG, "Created Session id=$sessionId groupId=$groupId")

                startService(sessionId, AccuracyProfile.STANDARD)
                Log.i(TAG, "LocationTrackingService started with sessionId=$sessionId profile=STANDARD")
            }
            _events.send(Event.NavigateToActiveRecording)
        }
    }

    companion object {
        private const val TAG = "TripLens/RecordingVM"
    }
}
