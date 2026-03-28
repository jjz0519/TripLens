package com.cooldog.triplens.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NoteType {
    @SerialName("text")  TEXT,
    @SerialName("voice") VOICE
}
