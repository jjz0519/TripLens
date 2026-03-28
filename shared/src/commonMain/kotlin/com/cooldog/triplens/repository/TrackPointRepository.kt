package com.cooldog.triplens.repository

import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode

class TrackPointRepository(private val db: AppDatabase) {

    fun insert(point: TrackPointInsert) {
        db.trackPointQueries.insert(
            point.sessionId, point.timestamp,
            point.latitude, point.longitude, point.altitude,
            point.accuracy.toDouble(), point.speed?.toDouble(),
            point.transportMode.name.lowercase(),
            // SQLite stores booleans as integers (1/0)
            if (point.isAutoPaused) 1L else 0L
        )
    }

    /** Inserts all points in a single DB transaction (max 10 for buffer flush). */
    fun insertBatch(points: List<TrackPointInsert>) {
        db.trackPointQueries.transaction {
            points.forEach { insert(it) }
        }
    }

    fun getBySession(sessionId: String): List<TrackPoint> =
        db.trackPointQueries.getBySessionId(sessionId).executeAsList().map { it.toModel() }

    fun getInRange(sessionId: String, fromMs: Long, toMs: Long): List<TrackPoint> =
        db.trackPointQueries.getBySessionIdAndTimeRange(sessionId, fromMs, toMs)
            .executeAsList().map { it.toModel() }

    private fun com.cooldog.triplens.db.Track_point.toModel() = TrackPoint(
        id = id,
        sessionId = session_id,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy.toFloat(),
        speed = speed?.toFloat(),
        transportMode = TransportMode.valueOf(transport_mode.uppercase()),
        isAutoPaused = is_auto_paused != 0L
    )
}
