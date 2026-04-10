package com.cooldog.triplens.ui.sessionreview

import com.cooldog.triplens.model.MediaReference
import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.TrackPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Bundles all external dependencies injected into [SessionReviewViewModel].
 *
 * Follows the same named-field data class pattern as [com.cooldog.triplens.ui.tripdetail.TripDetailDeps].
 *
 * @param sessionId   The Session ID from the navigation route argument. Passed via Koin's
 *                    `parametersOf()` so the ViewModel can be tested without SavedStateHandle.
 */
data class SessionReviewDeps(
    /** The Session ID from the navigation route argument. */
    val sessionId: String,

    /** Loads the session header (name, start/end times). */
    val getSessionFn: (id: String) -> Session?,

    /** Loads all GPS track points for the session, ordered by timestamp ASC. */
    val getTrackPointsFn: (sessionId: String) -> List<TrackPoint>,

    /** Loads all media references (photos / videos) for the session. */
    val getMediaRefsFn: (sessionId: String) -> List<MediaReference>,

    /** Loads all notes (text and voice) for the session. */
    val getNotesFn: (sessionId: String) -> List<Note>,

    /**
     * Resolves a voice note filename to its absolute file path.
     *
     * Backed by `context.filesDir.resolve("notes/$filename").absolutePath` in the Koin module.
     * Injected as a lambda so tests can return predictable fake paths without a Context.
     */
    val getAudioFilePathFn: (filename: String) -> String,

    /**
     * Localised error message shown in [SessionReviewViewModel.UiState.Error] when the session
     * ID from the navigation route does not match any session in the database.
     */
    val sessionNotFoundMessage: String = "Session not found",

    /** Injected so tests pass `{ FIXED_EPOCH }` for deterministic timestamps. */
    val clock: () -> Long = { System.currentTimeMillis() },

    /** Injected so tests pass a [kotlinx.coroutines.test.StandardTestDispatcher]. */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
