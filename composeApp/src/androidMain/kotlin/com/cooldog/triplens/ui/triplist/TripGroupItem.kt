package com.cooldog.triplens.ui.triplist

/**
 * UI model for a TripGroup card in the Trip List screen.
 *
 * Separates the UI layer from the raw [com.cooldog.triplens.repository.TripGroupWithStats]
 * DB result. Contains pre-formatted display strings and down-sampled trajectory data
 * ready for rendering.
 *
 * @param id                 TripGroup ID for navigation and actions.
 * @param name               Display name of the group (editable via rename dialog).
 * @param sessionCount       Number of sessions in the group.
 * @param dateRange          Pre-formatted date range string (e.g. "Apr 2 – Apr 5, 2026").
 * @param totalDistanceMeters Total haversine distance across all sessions.
 * @param photoCount         Number of photos across all sessions.
 * @param videoCount         Number of videos across all sessions.
 * @param noteCount          Number of notes (text + voice) across all sessions.
 * @param thumbnailPoints    Down-sampled lat/lng pairs for the Canvas trajectory thumbnail.
 */
data class TripGroupItem(
    val id: String,
    val name: String,
    val sessionCount: Long,
    val dateRange: String,
    val totalDistanceMeters: Double,
    val photoCount: Long,
    val videoCount: Long,
    val noteCount: Long,
    val thumbnailPoints: List<Pair<Double, Double>>,
)
