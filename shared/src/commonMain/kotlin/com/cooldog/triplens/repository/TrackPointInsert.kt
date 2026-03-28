package com.cooldog.triplens.repository

import com.cooldog.triplens.model.TransportMode

// Lightweight insert payload — avoids using the generated DB type in business logic.
data class TrackPointInsert(
    val sessionId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float,
    val speed: Float?,
    val transportMode: TransportMode,
    // Defaults to false; set to true only by LocationTrackingService during auto-pause.
    val isAutoPaused: Boolean = false
)
