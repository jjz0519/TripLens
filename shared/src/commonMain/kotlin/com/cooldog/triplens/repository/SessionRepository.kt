package com.cooldog.triplens.repository

import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.SessionStatus

class SessionRepository(private val db: AppDatabase) {

    fun createSession(id: String, groupId: String, name: String, startTime: Long) {
        db.sessionQueries.insert(id, groupId, name, startTime, null, "recording")
    }

    fun completeSession(id: String, endTime: Long) {
        db.sessionQueries.setEndTime(endTime, id)
    }

    fun markInterrupted(id: String) {
        db.sessionQueries.updateStatus("interrupted", id)
    }

    fun getActiveSession(): Session? =
        db.sessionQueries.getActiveSession().executeAsOneOrNull()?.toModel()

    fun getSessionById(id: String): Session? =
        db.sessionQueries.getById(id).executeAsOneOrNull()?.toModel()

    fun getSessionsByGroup(groupId: String): List<Session> =
        db.sessionQueries.getByGroupId(groupId).executeAsList().map { it.toModel() }

    private fun com.cooldog.triplens.db.Session.toModel() = Session(
        id = id,
        groupId = group_id,
        name = name,
        startTime = start_time,
        endTime = end_time,
        status = SessionStatus.valueOf(status.uppercase())
    )
}
