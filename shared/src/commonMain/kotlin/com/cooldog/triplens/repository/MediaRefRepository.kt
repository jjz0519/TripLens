package com.cooldog.triplens.repository

import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.model.LocationSource
import com.cooldog.triplens.model.MediaReference
import com.cooldog.triplens.model.MediaSource
import com.cooldog.triplens.model.MediaType

class MediaRefRepository(private val db: AppDatabase) {

    /**
     * Inserts a media reference only if no row with the same content_uri already exists.
     * Deduplication prevents double-counting when the gallery scanner runs repeatedly.
     */
    fun insertIfNotExists(
        id: String, sessionId: String, type: String, source: String,
        contentUri: String, filename: String, capturedAt: Long
    ) {
        val existing = db.mediaRefQueries.getByContentUri(contentUri).executeAsOneOrNull()
        if (existing == null) {
            db.mediaRefQueries.insert(
                id, sessionId, type, source, contentUri, filename,
                capturedAt, 0, null, null, null, null, null, null, null
            )
        }
    }

    fun getBySession(sessionId: String): List<MediaReference> =
        db.mediaRefQueries.getBySessionId(sessionId).executeAsList().map { it.toModel() }

    fun updateInferredLocation(id: String, lat: Double, lng: Double) {
        db.mediaRefQueries.updateInferredLocation(lat, lng, id)
    }

    private fun com.cooldog.triplens.db.Media_reference.toModel() = MediaReference(
        id = id,
        sessionId = session_id,
        type = MediaType.valueOf(type.uppercase()),
        source = MediaSource.valueOf(source.uppercase()),
        contentUri = content_uri,
        originalFilename = original_filename,
        capturedAt = captured_at,
        timestampOffset = timestamp_offset.toInt(),
        originalLat = original_lat,
        originalLng = original_lng,
        inferredLat = inferred_lat,
        inferredLng = inferred_lng,
        locationSource = location_source?.let { LocationSource.valueOf(it.uppercase()) },
        matchedSessionId = matched_session_id,
        matchedTrackpointId = matched_trackpoint_id
    )
}
