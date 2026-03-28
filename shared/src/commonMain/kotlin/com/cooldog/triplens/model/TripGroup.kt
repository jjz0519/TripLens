package com.cooldog.triplens.model

import kotlinx.serialization.Serializable

@Serializable
data class TripGroup(
    val id: String,
    val name: String,
    val createdAt: Long,   // epoch millis UTC
    val updatedAt: Long    // epoch millis UTC
)
