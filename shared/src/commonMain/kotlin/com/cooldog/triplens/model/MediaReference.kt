package com.cooldog.triplens.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaReference(
    val id: String,
    val sessionId: String,
    val type: MediaType,
    val source: MediaSource,
    val contentUri: String?,
    val originalFilename: String?,
    val capturedAt: Long,              // epoch millis UTC
    val timestampOffset: Int,          // seconds; camera clock drift vs phone time
    val originalLat: Double?,          // from EXIF, null if not present
    val originalLng: Double?,
    val inferredLat: Double?,          // from trajectory matching, null until computed
    val inferredLng: Double?,
    val locationSource: LocationSource?,
    val matchedSessionId: String?,
    val matchedTrackpointId: Long?
)
