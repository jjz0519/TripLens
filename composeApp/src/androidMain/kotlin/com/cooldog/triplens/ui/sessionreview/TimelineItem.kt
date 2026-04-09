package com.cooldog.triplens.ui.sessionreview

import com.cooldog.triplens.domain.Segment
import com.cooldog.triplens.ui.recording.MediaItem

/**
 * A single entry in the session review timeline, ordered by [timestampMs].
 *
 * ## Types
 * - [SegmentItem] — a transport mode segment (walking, driving, etc.) computed by
 *   [com.cooldog.triplens.domain.SegmentSmoother]. No map marker — segments span a
 *   time range rather than a single point.
 * - [MediaEntry] — a photo, video, text note, or voice note at a specific GPS location.
 *   May have an optional [lat]/[lng] for map marker placement. Voice notes carry an
 *   [audioFilePath] for playback in [MediaPreviewSheet].
 *
 * ## Rationale for wrapping MediaItem
 * [MediaItem] (from `ui/recording`) only carries display data (contentUri, preview text,
 * durationSeconds). GPS location and the resolved audio file path are not part of that
 * sealed interface because they are not needed during live recording. [MediaEntry] extends
 * [MediaItem] with these session-review-only fields without modifying the shared type.
 */
sealed interface TimelineItem {
    val id: String
    val timestampMs: Long

    /**
     * A smoothed transport mode segment spanning [timestampMs] to
     * [Segment.endTimestamp]. No GPS point — not tappable on the map.
     */
    data class SegmentItem(
        val segment: Segment,
    ) : TimelineItem {
        // Segments are identified by their start timestamp; they have no DB id.
        override val id = "segment_${segment.startTimestamp}"
        override val timestampMs = segment.startTimestamp
    }

    /**
     * A photo, video, text note, or voice note from the session.
     *
     * @param item            The display-oriented [MediaItem] (contentUri, preview, duration).
     * @param lat             Latitude for the map marker. Null = no marker rendered.
     * @param lng             Longitude for the map marker. Null = no marker rendered.
     * @param audioFilePath   Absolute path to the M4A file. Non-null only for [MediaItem.VoiceNote].
     * @param fullTextContent Full note content for [MediaItem.TextNote]. [MediaItem.TextNote.preview]
     *                        is truncated to 40 chars for the recording strip; this field carries
     *                        the untruncated content for the preview sheet.
     */
    data class MediaEntry(
        val item: MediaItem,
        val lat: Double?,
        val lng: Double?,
        val audioFilePath: String? = null,
        val fullTextContent: String? = null,
    ) : TimelineItem {
        override val id = item.id
        override val timestampMs = item.capturedAt
    }
}
