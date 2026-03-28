package com.cooldog.triplens.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Speed-based transport mode. Lowercase serial names must match DB CHECK constraints
// and index.json compact format (TDD Section 3.1 and 7.2).
@Serializable
enum class TransportMode {
    @SerialName("stationary") STATIONARY,
    @SerialName("walking")    WALKING,
    @SerialName("cycling")    CYCLING,
    @SerialName("driving")    DRIVING,
    @SerialName("fast_transit") FAST_TRANSIT
}
