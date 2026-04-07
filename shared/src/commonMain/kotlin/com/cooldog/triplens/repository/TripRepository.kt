package com.cooldog.triplens.repository

import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.model.TripGroup

/**
 * Pre-joined TripGroup row with aggregate stats computed entirely in SQL.
 * Avoids loading all track points into memory just to compute distance and counts.
 * Used by TripListScreen cards.
 */
data class TripGroupWithStats(
    val group: TripGroup,
    val sessionCount: Long,
    val earliestStart: Long?,
    val latestEnd: Long?,
    val totalDistanceMeters: Double,
    val photoCount: Long,
    val videoCount: Long,
    val noteCount: Long,
)

class TripRepository(private val db: AppDatabase) {

    fun createGroup(id: String, name: String, now: Long) {
        db.tripGroupQueries.insert(id, name, now, now)
    }

    fun renameGroup(id: String, newName: String, now: Long) {
        db.tripGroupQueries.updateName(newName, now, id)
    }

    fun deleteGroup(id: String) {
        db.tripGroupQueries.delete(id)
    }

    fun getAllGroups(): List<TripGroup> =
        db.tripGroupQueries.getAll().executeAsList().map { it.toModel() }

    fun getGroupById(id: String): TripGroup? =
        db.tripGroupQueries.getById(id).executeAsOneOrNull()?.toModel()

    /**
     * Returns all groups with pre-computed aggregate stats (session count, total distance,
     * media counts, note counts) from a single SQL query. Used by TripListScreen to render
     * group cards without loading full track_point arrays into memory.
     */
    fun getAllGroupsWithStats(): List<TripGroupWithStats> =
        db.tripGroupQueries.getAllGroupsWithStats().executeAsList().map {
            TripGroupWithStats(
                group = TripGroup(it.id, it.name, it.created_at, it.updated_at),
                sessionCount = it.session_count,
                earliestStart = it.earliest_start,
                latestEnd = it.latest_end,
                totalDistanceMeters = it.total_distance_meters,
                photoCount = it.photo_count,
                videoCount = it.video_count,
                noteCount = it.note_count,
            )
        }

    private fun com.cooldog.triplens.db.Trip_group.toModel() = TripGroup(
        id = id,
        name = name,
        createdAt = created_at,
        updatedAt = updated_at
    )
}

