package com.cooldog.triplens.repository

import com.cooldog.triplens.db.TripLensDatabase
import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.NoteType

class NoteRepository(private val db: TripLensDatabase) {

    fun createTextNote(
        id: String, sessionId: String, content: String,
        createdAt: Long, latitude: Double, longitude: Double
    ) {
        db.noteQueries.insert(id, sessionId, "text", content, null, null, createdAt, latitude, longitude)
    }

    fun createVoiceNote(
        id: String, sessionId: String, audioFilename: String,
        durationSeconds: Int, createdAt: Long, latitude: Double, longitude: Double
    ) {
        db.noteQueries.insert(
            id, sessionId, "voice", null, audioFilename,
            durationSeconds.toLong(), createdAt, latitude, longitude
        )
    }

    fun getBySession(sessionId: String): List<Note> =
        db.noteQueries.getBySessionId(sessionId).executeAsList().map { it.toModel() }

    fun delete(id: String) {
        db.noteQueries.delete(id)
    }

    private fun com.cooldog.triplens.db.Note.toModel() = Note(
        id = id,
        sessionId = session_id,
        type = NoteType.valueOf(type.uppercase()),
        content = content,
        audioFilename = audio_filename,
        durationSeconds = duration_seconds?.toInt(),
        createdAt = created_at,
        latitude = latitude,
        longitude = longitude
    )
}
