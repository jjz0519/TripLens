package com.cooldog.triplens.repository

import com.cooldog.triplens.db.TripLensDatabase
import com.cooldog.triplens.model.TripGroup

class TripRepository(private val db: TripLensDatabase) {

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

    private fun com.cooldog.triplens.db.Trip_group.toModel() = TripGroup(
        id = id,
        name = name,
        createdAt = created_at,
        updatedAt = updated_at
    )
}
