package com.cooldog.triplens.ui.recording

/**
 * Unified display model for the recording screen's media strip.
 *
 * Merges [com.cooldog.triplens.model.MediaReference] (photos/videos from the gallery scanner)
 * and [com.cooldog.triplens.model.Note] (text and voice notes recorded during the session)
 * into one list that can be sorted by time and capped at 10 items.
 *
 * [capturedAt] is epoch millis used for sorting (newest-first in the strip).
 */
sealed interface MediaItem {
    val id: String
    val capturedAt: Long

    /** Photo from the phone gallery with a resolvable content URI. */
    data class Photo(
        override val id: String,
        override val capturedAt: Long,
        /** Android content URI string; rendered via Coil AsyncImage. */
        val contentUri: String,
    ) : MediaItem

    /** Video from the phone gallery with a resolvable content URI. */
    data class Video(
        override val id: String,
        override val capturedAt: Long,
        val contentUri: String,
    ) : MediaItem

    /** Text note created during recording. [preview] is the first ~40 chars for strip display. */
    data class TextNote(
        override val id: String,
        override val capturedAt: Long,
        val preview: String,
    ) : MediaItem

    /** Voice note recorded during the session. [durationSeconds] drives the duration label. */
    data class VoiceNote(
        override val id: String,
        override val capturedAt: Long,
        val durationSeconds: Int,
    ) : MediaItem
}
