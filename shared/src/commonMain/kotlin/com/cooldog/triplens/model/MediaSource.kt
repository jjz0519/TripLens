package com.cooldog.triplens.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaSource {
    @SerialName("phone_gallery")    PHONE_GALLERY,
    @SerialName("external_camera")  EXTERNAL_CAMERA
}
