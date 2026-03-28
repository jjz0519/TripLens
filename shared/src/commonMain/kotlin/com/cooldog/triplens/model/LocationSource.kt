package com.cooldog.triplens.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LocationSource {
    @SerialName("exif")       EXIF,
    @SerialName("trajectory") TRAJECTORY
}
