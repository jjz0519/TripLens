package com.cooldog.triplens.ui.tripdetail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.domain.SegmentSmoother
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.ui.common.ExportState
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

/**
 * ViewModel for the TripGroup Detail screen — loads sessions with transport mode breakdowns.
 *
 * ## Data loading (eager, Q7 = A)
 * On init, loads all sessions for the group, then for each session loads all track points
 * and processes them through [SegmentSmoother.smooth] to compute transport mode breakdowns.
 *
 * ## Pattern
 * Same as [com.cooldog.triplens.ui.triplist.TripListViewModel]:
 * - `sealed interface UiState` + `sealed interface Event`
 * - [MutableStateFlow] + [Channel]
 * - [TripDetailDeps] bundles all lambdas
 */
class TripDetailViewModel(private val deps: TripDetailDeps) : ViewModel() {

    // ── UiState ─────────────────────────────────────────────────────────────────

    sealed interface UiState {
        data object Loading : UiState

        data class Loaded(
            val groupName: String,
            val totalDistanceMeters: Double,
            val totalDurationSeconds: Long,
            val sessionCount: Int,
            val sessions: List<SessionItem>,
        ) : UiState

        data class Error(val message: String) : UiState
    }

    // ── Events ───────────────────────────────────────────────────────────────────

    sealed interface Event {
        data class ShowSnackbar(val message: String) : Event

        /**
         * Emitted on successful export. [path] is the absolute filesystem path to the
         * exported .triplens archive. The Screen converts it to a content:// URI via
         * FileProvider and fires an ACTION_SEND chooser intent.
         *
         * The path is passed as a plain String to keep android.net.Uri out of the ViewModel,
         * consistent with the principle that Intent construction is a View-layer concern.
         */
        data class ShareFile(val path: String) : Event
    }

    // ── State and event plumbing ─────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        loadDetail()
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /** Renames a session and reloads the detail view. */
    fun onRenameSession(id: String, newName: String) {
        viewModelScope.launch {
            try {
                withContext(deps.ioDispatcher) {
                    deps.renameSessionFn(id, newName)
                }
                Log.i(TAG, "Renamed session id=$id to '$newName'")
                loadDetail()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session id=$id", e)
                _events.send(Event.ShowSnackbar("Failed to rename session: ${e.message}"))
            }
        }
    }

    /**
     * Exports the entire group and triggers the Android share chooser on success.
     *
     * ## State transitions
     * - Sets [exportState] to [ExportState.InProgress] immediately (disables the export button).
     * - On success: emits [Event.ShareFile] with the archive path, resets to [ExportState.Idle].
     * - On failure: emits [Event.ShowSnackbar] with the error, sets [ExportState.Error].
     *
     * Guard against double-taps: if an export is already [ExportState.InProgress] the call is
     * a no-op — the button is disabled in the UI, but this check prevents races if the VM is
     * called programmatically.
     */
    fun onExportGroup() {
        // compareAndSet prevents a double-tap race: two rapid calls on the same main-thread
        // frame would both pass a plain `if (_exportState.value is InProgress) return` check
        // before either sets the state. compareAndSet is atomic — only one wins.
        val current = _exportState.value
        if (current is ExportState.InProgress) return
        if (!_exportState.compareAndSet(current, ExportState.InProgress)) return
        viewModelScope.launch {
            try {
                val result = withContext(deps.ioDispatcher) {
                    deps.exportFn(deps.groupId, deps.clock())
                }
                Log.i(TAG, "Exported group=${deps.groupId} path=${result.path} size=${result.sizeBytes}")
                _events.send(Event.ShareFile(result.path))
                _exportState.value = ExportState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Export failed for group=${deps.groupId}", e)
                _exportState.value = ExportState.Error(e.message ?: "Unknown error")
                _events.send(Event.ShowSnackbar("Export failed: ${e.message}"))
                // Reset to Idle so the button re-enables and the error state doesn't linger
                // past the snackbar dismissal (which the screen handles via ShowSnackbar event).
                _exportState.value = ExportState.Idle
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun loadDetail() {
        viewModelScope.launch {
            try {
                val group = withContext(deps.ioDispatcher) {
                    deps.getGroupByIdFn(deps.groupId)
                } ?: run {
                    _uiState.value = UiState.Error(deps.tripNotFoundMessage)
                    return@launch
                }

                val sessions = withContext(deps.ioDispatcher) {
                    deps.getSessionsByGroupFn(deps.groupId)
                }

                // Eager loading: load all track points for all sessions upfront.
                val sessionItems = sessions.map { session ->
                    val points = withContext(deps.ioDispatcher) {
                        deps.getTrackPointsFn(session.id)
                    }

                    // Process through SegmentSmoother for transport mode breakdown.
                    val segments = SegmentSmoother.smooth(points, MIN_SEGMENT_POINTS)
                    val totalDistance = segments.sumOf { it.distanceMeters }
                    // Local val avoids smart-cast issue: Session.endTime is a public
                    // property from a different module, so Kotlin can't smart-cast it.
                    val endMs = session.endTime
                    val durationSeconds = if (endMs != null) {
                        (endMs - session.startTime) / 1_000L
                    } else {
                        0L
                    }

                    // Build transport breakdown: group segments by mode, sum distances.
                    val breakdown = segments
                        .groupBy { it.mode }
                        .map { (mode, segs) ->
                            TransportStat(mode = mode, distanceMeters = segs.sumOf { it.distanceMeters })
                        }
                        // Sort by distance descending so the dominant mode appears first.
                        .sortedByDescending { it.distanceMeters }

                    SessionItem(
                        id = session.id,
                        name = session.name,
                        startTime = session.startTime,
                        endTime = session.endTime,
                        dateTimeRange = formatSessionTimeRange(session.startTime, session.endTime),
                        durationSeconds = durationSeconds,
                        distanceMeters = totalDistance,
                        transportBreakdown = breakdown,
                    )
                }

                val totalDistance = sessionItems.sumOf { it.distanceMeters }
                val totalDuration = sessionItems.sumOf { it.durationSeconds }

                _uiState.value = UiState.Loaded(
                    groupName = group.name,
                    totalDistanceMeters = totalDistance,
                    totalDurationSeconds = totalDuration,
                    sessionCount = sessions.size,
                    sessions = sessionItems,
                )
                Log.d(TAG, "Loaded ${sessions.size} sessions for group=${deps.groupId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load detail for group=${deps.groupId}", e)
                _uiState.value = UiState.Error("Failed to load trip: ${e.message}")
            }
        }
    }

    /**
     * Formats a session's time range for display.
     * - "Apr 2, 10:30 – 12:45" (same day)
     * - "Apr 2, 10:30 – Apr 3, 12:45" (different days)
     * - "Apr 2, 10:30 – In progress" (no end time)
     */
    private fun formatSessionTimeRange(startMs: Long, endMs: Long?): String {
        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startDate = dateFmt.format(Date(startMs))
        val startTime = timeFmt.format(Date(startMs))

        if (endMs == null) return "$startDate, $startTime – ${deps.sessionInProgressLabel}"

        val endDate = dateFmt.format(Date(endMs))
        val endTime = timeFmt.format(Date(endMs))

        return if (startDate == endDate) {
            "$startDate, $startTime – $endTime"
        } else {
            "$startDate, $startTime – $endDate, $endTime"
        }
    }

    companion object {
        private const val TAG = "TripLens/TripDetailVM"
        /** minSegmentPoints for SegmentSmoother — 3 points ≈ 24s at STANDARD GPS interval. */
        private const val MIN_SEGMENT_POINTS = 3
    }
}

// ── UI models ────────────────────────────────────────────────────────────────────

/**
 * UI model for a session row in the TripDetail screen.
 */
data class SessionItem(
    val id: String,
    val name: String,
    val startTime: Long,
    val endTime: Long?,
    val dateTimeRange: String,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val transportBreakdown: List<TransportStat>,
)

/**
 * A transport mode with its accumulated distance for display in the session row.
 */
data class TransportStat(
    val mode: TransportMode,
    val distanceMeters: Double,
)
