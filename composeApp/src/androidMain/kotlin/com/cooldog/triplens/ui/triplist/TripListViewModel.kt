package com.cooldog.triplens.ui.triplist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.repository.TripGroupWithStats
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
 * ViewModel for the Trip List screen — loads all TripGroups with aggregate stats
 * and exposes actions for rename, delete, and export.
 *
 * ## Pattern
 * - `sealed interface UiState` + `sealed interface Event` nested inside the VM
 * - [MutableStateFlow] for state, [Channel] for one-shot events
 * - All IO via `withContext(deps.ioDispatcher)`
 * - [TripListDeps] bundles all external lambdas
 *
 * ## Data loading
 * On init, loads all groups with stats from SQL (single aggregate query) and
 * down-samples track points for each group's trajectory thumbnail.
 */
class TripListViewModel(private val deps: TripListDeps) : ViewModel() {

    // ── UiState ─────────────────────────────────────────────────────────────────

    sealed interface UiState {
        /** Initial loading spinner. */
        data object Loading : UiState

        /** Data loaded successfully. [groups] may be empty if no trips exist yet. */
        data class Loaded(val groups: List<TripGroupItem>) : UiState

        /** Unrecoverable error during data load. */
        data class Error(val message: String) : UiState
    }

    // ── Events ───────────────────────────────────────────────────────────────────

    sealed interface Event {
        /** Show a snackbar with an informational or error message. */
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
        loadGroups()
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /** Deletes a group and reloads the list. Shows a snackbar on failure. */
    fun onDeleteGroup(id: String) {
        viewModelScope.launch {
            try {
                withContext(deps.ioDispatcher) { deps.deleteGroupFn(id) }
                Log.i(TAG, "Deleted group id=$id")
                loadGroups()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete group id=$id", e)
                _events.send(Event.ShowSnackbar("Failed to delete trip: ${e.message}"))
            }
        }
    }

    /** Renames a group and reloads the list. Shows a snackbar on failure. */
    fun onRenameGroup(id: String, newName: String) {
        viewModelScope.launch {
            try {
                withContext(deps.ioDispatcher) {
                    deps.renameGroupFn(id, newName, deps.clock())
                }
                Log.i(TAG, "Renamed group id=$id to '$newName'")
                loadGroups()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename group id=$id", e)
                _events.send(Event.ShowSnackbar("Failed to rename trip: ${e.message}"))
            }
        }
    }

    /**
     * Triggers export for a group and fires the Android share chooser on success.
     *
     * ## State transitions
     * - Sets [exportState] to [ExportState.InProgress] immediately.
     * - On success: emits [Event.ShareFile] with the archive path, resets to [ExportState.Idle].
     * - On failure: emits [Event.ShowSnackbar] with the error, sets [ExportState.Error].
     *
     * Guard: if an export is already [ExportState.InProgress] the call is a no-op to prevent
     * concurrent exports (only one trip can be exported at a time).
     */
    fun onExportGroup(id: String) {
        // compareAndSet prevents a double-tap race: two rapid calls on the same main-thread
        // frame would both pass a plain `if (_exportState.value is InProgress) return` check
        // before either sets the state. compareAndSet is atomic — only one wins.
        val current = _exportState.value
        if (current is ExportState.InProgress) return
        if (!_exportState.compareAndSet(current, ExportState.InProgress)) return
        viewModelScope.launch {
            try {
                val result = withContext(deps.ioDispatcher) {
                    deps.exportFn(id, deps.clock())
                }
                Log.i(TAG, "Exported group id=$id path=${result.path} size=${result.sizeBytes}")
                _events.send(Event.ShareFile(result.path))
                _exportState.value = ExportState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Export failed for group id=$id", e)
                _exportState.value = ExportState.Error(e.message ?: "Unknown error")
                _events.send(Event.ShowSnackbar("Export failed: ${e.message}"))
                // Reset to Idle so the button re-enables and the error state doesn't linger
                // past the snackbar dismissal (which the screen handles via ShowSnackbar event).
                _exportState.value = ExportState.Idle
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /** Loads all groups with stats and builds UI models. */
    private fun loadGroups() {
        viewModelScope.launch {
            try {
                val groupsWithStats = withContext(deps.ioDispatcher) {
                    deps.getAllGroupsWithStatsFn()
                }

                val items = groupsWithStats.map { it.toItem() }
                _uiState.value = UiState.Loaded(items)
                Log.d(TAG, "Loaded ${items.size} groups")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load groups", e)
                _uiState.value = UiState.Error("Failed to load trips: ${e.message}")
            }
        }
    }

    /**
     * Converts a [TripGroupWithStats] to a [TripGroupItem] UI model.
     * Formats the date range for display and loads down-sampled trajectory points.
     */
    private suspend fun TripGroupWithStats.toItem(): TripGroupItem {
        val dateRange = formatDateRange(earliestStart, latestEnd)

        // Load and down-sample track points for the trajectory thumbnail.
        // Take every Nth point to cap at ~50 points for Canvas rendering.
        val allPoints = withContext(deps.ioDispatcher) {
            deps.getTrackPointsByGroupFn(group.id)
        }
        val step = (allPoints.size / MAX_THUMBNAIL_POINTS).coerceAtLeast(1)
        val thumbnailPoints = allPoints
            .filterIndexed { index, _ -> index % step == 0 }
            .map { it.latitude to it.longitude }

        return TripGroupItem(
            id = group.id,
            name = group.name,
            sessionCount = sessionCount,
            dateRange = dateRange,
            totalDistanceMeters = totalDistanceMeters,
            photoCount = photoCount,
            videoCount = videoCount,
            noteCount = noteCount,
            thumbnailPoints = thumbnailPoints,
        )
    }

    /**
     * Formats a date range for display on the card.
     * - Same day: "Apr 2, 2026"
     * - Different days: "Apr 2 – Apr 5, 2026"
     * - No dates: "No sessions"
     */
    private fun formatDateRange(earliestMs: Long?, latestMs: Long?): String {
        if (earliestMs == null) return deps.noSessionsLabel
        val dayFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val fullFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val startStr = dayFmt.format(Date(earliestMs))
        val endStr = fullFmt.format(Date(latestMs ?: earliestMs))

        // Check if same day by comparing formatted day strings
        val startDay = dayFmt.format(Date(earliestMs))
        val endDay = dayFmt.format(Date(latestMs ?: earliestMs))
        return if (startDay == endDay) {
            fullFmt.format(Date(earliestMs))
        } else {
            "$startStr – $endStr"
        }
    }

    companion object {
        private const val TAG = "TripLens/TripListVM"
        /** Maximum number of lat/lng points retained for the Canvas trajectory thumbnail. */
        private const val MAX_THUMBNAIL_POINTS = 50
    }
}
