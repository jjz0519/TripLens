package com.cooldog.triplens.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType {
    @SerialName("photo") PHOTO,
    @SerialName("video") VIDEO
}
