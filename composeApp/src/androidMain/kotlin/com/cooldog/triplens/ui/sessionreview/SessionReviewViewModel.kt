package com.cooldog.triplens.ui.sessionreview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.domain.HaversineUtils
import com.cooldog.triplens.domain.Segment
import com.cooldog.triplens.domain.SegmentSmoother
import com.cooldog.triplens.model.MediaReference
import com.cooldog.triplens.model.MediaType
import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.ui.recording.MediaItem
import com.cooldog.triplens.ui.tripdetail.TransportStat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Session Review screen.
 *
 * ## Responsibilities
 * 1. Load all session data on init (track points, media refs, notes) via one-shot IO reads.
 * 2. Run [SegmentSmoother] to produce the transport mode timeline.
 * 3. Merge segments + media items into a sorted [TimelineItem] list for the timeline column.
 * 4. Handle map marker taps ([onMarkerTapped]) — scrolls the timeline to the matching item.
 * 5. Handle timeline item taps ([onTimelineItemTapped]) — opens [MediaPreviewSheet].
 *
 * ## Duration calculation
 * Uses wall-clock duration: `(session.endTime - session.startTime) / 1000`. This matches
 * [com.cooldog.triplens.ui.tripdetail.TripDetailViewModel] and is consistent with the stats
 * shown on the detail screen. In-progress sessions (endTime = null) show 0s.
 *
 * ## Distance calculation
 * Re-computed from track points via [HaversineUtils.totalDistance] on all non-auto-paused
 * points. This matches the value stored in `session.distance_meters` but is computed fresh
 * to avoid stale values if the DB column was not updated (e.g. session was interrupted).
 *
 * ## Media location resolution (Q4)
 * [com.cooldog.triplens.model.MediaReference] location priority:
 *   1. [originalLat/Lng] — EXIF GPS, more reliable for phone gallery photos captured with
 *      GPS enabled. Most phone photos will have this.
 *   2. [inferredLat/Lng] — trajectory-matched. Used when EXIF location is absent (camera GPS
 *      disabled or external camera photo before desktop import).
 *   External camera photos with neither field populated get no map marker.
 */
class SessionReviewViewModel(private val deps: SessionReviewDeps) : ViewModel() {

    // ── UiState ─────────────────────────────────────────────────────────────────

    sealed interface UiState {
        data object Loading : UiState

        data class Loaded(
            val session: Session,
            /** Raw GPS points for the map polyline (includes auto-paused for polyline gap logic). */
            val trackPoints: List<TrackPoint>,
            /** Merged, timestamp-sorted list of segments and media entries for the timeline column. */
            val timelineItems: List<TimelineItem>,
            /** Transport breakdown for the stats header, sorted by distance descending. */
            val transportBreakdown: List<TransportStat>,
            /** Haversine sum of non-auto-paused track points. */
            val totalDistanceMeters: Double,
            /** Wall-clock duration in seconds: (endTime - startTime) / 1000. Zero if in-progress. */
            val durationSeconds: Long,
            /**
             * ID of the timeline media entry currently highlighted.
             * Set by [onMarkerTapped]; drives [LazyListState.scrollToItem] in the screen.
             * Null = no highlight.
             */
            val selectedMediaId: String?,
            /**
             * The media entry whose preview sheet is currently open.
             * Set by [onTimelineItemTapped]; cleared by [onPreviewDismissed].
             */
            val previewEntry: TimelineItem.MediaEntry?,
        ) : UiState

        data class Error(val message: String) : UiState
    }

    // ── Events ───────────────────────────────────────────────────────────────────

    sealed interface Event {
        data class ShowSnackbar(val message: String) : Event
    }

    // ── State and event plumbing ─────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        loadSession()
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Called when the user taps a media marker on the map.
     *
     * Sets [UiState.Loaded.selectedMediaId] so the screen can scroll the timeline to
     * the matching item and highlight it. Does NOT open the preview sheet (Q1 = option A:
     * the user taps the timeline row again to open the preview).
     */
    fun onMarkerTapped(mediaId: String) {
        val current = _uiState.value as? UiState.Loaded ?: return
        _uiState.value = current.copy(selectedMediaId = mediaId)
        Log.d(TAG, "Map marker tapped: mediaId=$mediaId")
    }

    /**
     * Called when the user taps a [TimelineItem.MediaEntry] row in the timeline.
     * Opens [MediaPreviewSheet] by setting [UiState.Loaded.previewEntry].
     */
    fun onTimelineItemTapped(entry: TimelineItem.MediaEntry) {
        val current = _uiState.value as? UiState.Loaded ?: return
        _uiState.value = current.copy(previewEntry = entry)
        Log.d(TAG, "Timeline item tapped: id=${entry.id}")
    }

    /**
     * Called when the user dismisses [MediaPreviewSheet].
     * Clears [UiState.Loaded.previewEntry] so the sheet exits composition.
     */
    fun onPreviewDismissed() {
        val current = _uiState.value as? UiState.Loaded ?: return
        _uiState.value = current.copy(previewEntry = null)
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun loadSession() {
        viewModelScope.launch {
            try {
                // ── 1. Load session header ────────────────────────────────────────
                val session = withContext(deps.ioDispatcher) {
                    deps.getSessionFn(deps.sessionId)
                } ?: run {
                    Log.e(TAG, "Session not found: id=${deps.sessionId}")
                    _uiState.value = UiState.Error(deps.sessionNotFoundMessage)
                    return@launch
                }

                // ── 2. Load track points, media refs, notes in parallel ───────────
                // Sequential is fine here — each call is a fast SQLite executeAsList.
                val trackPoints = withContext(deps.ioDispatcher) {
                    deps.getTrackPointsFn(deps.sessionId)
                }
                val mediaRefs = withContext(deps.ioDispatcher) {
                    deps.getMediaRefsFn(deps.sessionId)
                }
                val notes = withContext(deps.ioDispatcher) {
                    deps.getNotesFn(deps.sessionId)
                }

                // ── 3. Run SegmentSmoother for transport timeline + breakdown ──────
                val segments = SegmentSmoother.smooth(trackPoints, MIN_SEGMENT_POINTS)
                val transportBreakdown = segments
                    .groupBy { it.mode }
                    .map { (mode, segs) ->
                        TransportStat(mode = mode, distanceMeters = segs.sumOf { it.distanceMeters })
                    }
                    .sortedByDescending { it.distanceMeters }

                // ── 4. Compute distance (non-auto-paused points only) ─────────────
                val activePoints = trackPoints.filter { !it.isAutoPaused }
                val totalDistance = HaversineUtils.totalDistance(activePoints)

                // ── 5. Wall-clock duration ────────────────────────────────────────
                val endMs = session.endTime
                val durationSeconds = if (endMs != null) {
                    (endMs - session.startTime) / 1_000L
                } else {
                    0L
                }

                // ── 6. Build timeline items ───────────────────────────────────────
                val timelineItems = buildTimeline(segments, mediaRefs, notes)

                _uiState.value = UiState.Loaded(
                    session = session,
                    trackPoints = trackPoints,
                    timelineItems = timelineItems,
                    transportBreakdown = transportBreakdown,
                    totalDistanceMeters = totalDistance,
                    durationSeconds = durationSeconds,
                    selectedMediaId = null,
                    previewEntry = null,
                )
                Log.i(
                    TAG,
                    "Loaded session=${deps.sessionId}: " +
                        "${trackPoints.size} pts, ${segments.size} segs, " +
                        "${mediaRefs.size} media, ${notes.size} notes",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load session=${deps.sessionId}", e)
                _uiState.value = UiState.Error("Failed to load session: ${e.message}")
            }
        }
    }

    /**
     * Merges [Segment]s, [com.cooldog.triplens.model.MediaReference]s, and
     * [com.cooldog.triplens.model.Note]s into a single timeline sorted by timestamp.
     *
     * ## MediaReference location (Q4)
     * Uses [MediaReference.originalLat/Lng] (EXIF) first; falls back to
     * [MediaReference.inferredLat/Lng] (trajectory-matched). Items with no location at all
     * still appear in the timeline but have null lat/lng (no map marker rendered).
     *
     * ## contentUri null guard
     * [MediaReference.contentUri] can be null for external camera imports before the desktop
     * tool has run. Such items are skipped — they cannot be displayed without a URI.
     */
    private fun buildTimeline(
        segments: List<Segment>,
        mediaRefs: List<MediaReference>,
        notes: List<Note>,
    ): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()

        // Segments — no GPS marker, just timeline cards.
        segments.forEach { segment ->
            items.add(TimelineItem.SegmentItem(segment))
        }

        // Media references — skip items with no content URI (not displayable).
        mediaRefs.forEach { ref ->
            val contentUri = ref.contentUri ?: return@forEach
            // Prefer EXIF location; fall back to trajectory-inferred (see kdoc above).
            val lat = ref.originalLat ?: ref.inferredLat
            val lng = ref.originalLng ?: ref.inferredLng
            val item = when (ref.type) {
                MediaType.PHOTO -> MediaItem.Photo(ref.id, ref.capturedAt, contentUri)
                MediaType.VIDEO -> MediaItem.Video(ref.id, ref.capturedAt, contentUri)
            }
            items.add(TimelineItem.MediaEntry(item = item, lat = lat, lng = lng))
        }

        // Notes — always have GPS coordinates recorded at creation time.
        notes.forEach { note ->
            val item = when (note.type) {
                NoteType.TEXT -> MediaItem.TextNote(
                    id = note.id,
                    capturedAt = note.createdAt,
                    // First 40 chars for the timeline row label; full content stored separately.
                    preview = note.content?.take(40) ?: "",
                )
                NoteType.VOICE -> MediaItem.VoiceNote(
                    id = note.id,
                    capturedAt = note.createdAt,
                    durationSeconds = note.durationSeconds ?: 0,
                )
            }
            // Local val required because note.audioFilename is a public property from a
            // different module — Kotlin cannot smart-cast it directly after the null check.
            val audioFilename = note.audioFilename
            val audioFilePath = if (note.type == NoteType.VOICE && audioFilename != null) {
                deps.getAudioFilePathFn(audioFilename)
            } else {
                null
            }
            items.add(
                TimelineItem.MediaEntry(
                    item = item,
                    lat = note.latitude,
                    lng = note.longitude,
                    audioFilePath = audioFilePath,
                    // Full text for the preview sheet; preview is capped at 40 chars for the strip.
                    fullTextContent = if (note.type == NoteType.TEXT) note.content else null,
                )
            )
        }

        return items.sortedBy { it.timestampMs }
    }

    companion object {
        private const val TAG = "TripLens/SessionReviewVM"
        /** minSegmentPoints for SegmentSmoother — 3 points ≈ 24s at STANDARD GPS interval. */
        private const val MIN_SEGMENT_POINTS = 3
    }
}
