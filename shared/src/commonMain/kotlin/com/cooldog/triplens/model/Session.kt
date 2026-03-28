package com.cooldog.triplens.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val groupId: String,
    val name: String,
    val startTime: Long,         // epoch millis UTC
    val endTime: Long?,          // null while recording
    val status: SessionStatus
)
