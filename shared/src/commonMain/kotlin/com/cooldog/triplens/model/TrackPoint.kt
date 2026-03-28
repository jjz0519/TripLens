package com.cooldog.triplens.model

import kotlinx.serialization.Serializable

@Serializable
data class TrackPoint(
    val id: Long,
    val sessionId: String,
    val timestamp: Long,         // epoch millis UTC
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,       // null if GPS has no altitude fix
    val accuracy: Float,         // metres, horizontal accuracy radius
    val speed: Float?,           // m/s, null if not provided by GPS
    val transportMode: TransportMode
)
