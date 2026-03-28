package com.cooldog.triplens.model

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    val sessionId: String,
    val type: NoteType,
    val content: String?,           // null for voice notes
    val audioFilename: String?,     // null for text notes; e.g. "note_{uuid}.m4a"
    val durationSeconds: Int?,      // null for text notes
    val createdAt: Long,            // epoch millis UTC
    val latitude: Double,
    val longitude: Double
)
