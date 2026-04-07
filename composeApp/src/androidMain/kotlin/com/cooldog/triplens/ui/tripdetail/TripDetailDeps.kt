package com.cooldog.triplens.ui.tripdetail

import com.cooldog.triplens.export.ExportResult
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TripGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Bundles all external dependencies injected into [TripDetailViewModel].
 *
 * Follows the same named-field data class pattern as
 * [com.cooldog.triplens.ui.recording.RecordingDeps] and [com.cooldog.triplens.ui.triplist.TripListDeps].
 *
 * @param groupId  The TripGroup ID from the navigation route argument. Passed via Koin's
 *                 `parametersOf()` which is the cross-platform Koin API (works in commonMain,
 *                 future iOS).
 */
data class TripDetailDeps(
    /** The TripGroup ID from the navigation route argument. */
    val groupId: String,

    /** Loads a TripGroup by ID for the header display. */
    val getGroupByIdFn: (id: String) -> TripGroup?,

    /** Loads all sessions for the group, ordered by start time. */
    val getSessionsByGroupFn: (groupId: String) -> List<Session>,

    /** Loads all track points for a session. Used for transport mode breakdown computation. */
    val getTrackPointsFn: (sessionId: String) -> List<TrackPoint>,

    /** Renames a session (TripDetailScreen three-dot menu → Rename dialog). */
    val renameSessionFn: (id: String, newName: String) -> Unit,

    /** Calls [com.cooldog.triplens.export.ExportUseCase.export]. */
    val exportFn: suspend (groupId: String, nowMs: Long) -> ExportResult,

    /** Injected so tests pass `{ FIXED_EPOCH }` for deterministic timestamps. */
    val clock: () -> Long = { System.currentTimeMillis() },

    /** Injected so tests pass a [kotlinx.coroutines.test.StandardTestDispatcher]. */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
