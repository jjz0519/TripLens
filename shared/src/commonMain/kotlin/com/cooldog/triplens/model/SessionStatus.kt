package com.cooldog.triplens.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionStatus {
    @SerialName("recording")    RECORDING,
    @SerialName("completed")    COMPLETED,
    @SerialName("interrupted")  INTERRUPTED
}
