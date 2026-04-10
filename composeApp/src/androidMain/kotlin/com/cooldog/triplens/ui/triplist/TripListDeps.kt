package com.cooldog.triplens.ui.triplist

import com.cooldog.triplens.export.ExportResult
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.repository.TripGroupWithStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Bundles all external dependencies injected into [TripListViewModel].
 *
 * Follows the same named-field data class pattern as
 * [com.cooldog.triplens.ui.recording.RecordingDeps] to prevent accidental lambda swap bugs
 * (several lambdas share identical functional types).
 */
data class TripListDeps(
    /** Loads all groups with pre-computed aggregate stats from SQL. */
    val getAllGroupsWithStatsFn: () -> List<TripGroupWithStats>,

    /**
     * Loads all track points for all sessions in a group. Used to generate the
     * trajectory thumbnail on the TripList card. Points are down-sampled before rendering.
     */
    val getTrackPointsByGroupFn: (groupId: String) -> List<TrackPoint>,

    /** Deletes a TripGroup and cascades to all sessions, track points, media, and notes. */
    val deleteGroupFn: (id: String) -> Unit,

    /** Renames a TripGroup. [now] is the current epoch millis UTC for updated_at. */
    val renameGroupFn: (id: String, newName: String, now: Long) -> Unit,

    /**
     * Calls [com.cooldog.triplens.export.ExportUseCase.export]. This is a suspend function
     * because the export pipeline performs file I/O and zipping.
     */
    val exportFn: suspend (groupId: String, nowMs: Long) -> ExportResult,

    /**
     * Localised label returned by [TripListViewModel.formatDateRange] when a group has no
     * sessions yet (no start date available). Injected so tests can assert against a fixed
     * string without depending on Android resources.
     */
    val noSessionsLabel: String = "No sessions",

    /** Injected so tests pass `{ FIXED_EPOCH }` for deterministic timestamps. */
    val clock: () -> Long = { System.currentTimeMillis() },

    /** Injected so tests pass a [kotlinx.coroutines.test.StandardTestDispatcher]. */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
