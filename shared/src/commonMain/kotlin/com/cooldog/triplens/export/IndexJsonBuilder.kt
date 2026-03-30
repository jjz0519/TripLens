package com.cooldog.triplens.export

import com.cooldog.triplens.model.Note
import com.cooldog.triplens.model.NoteType
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TripGroup
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the `index.json` document for a `.triplens` export archive.
 *
 * ## Schema (schema_version: 1)
 * - Non-track timestamps are ISO 8601 UTC strings (human-readable, timezone-unambiguous).
 * - Track points use compact single-character keys (`t`, `lat`, `lng`, `alt`, `acc`, `spd`,
 *   `mode`) to keep the JSON small for sessions with thousands of points.
 * - `t` is epoch milliseconds (Long) — the only field that stays as a number, since it is
 *   queried by downstream tools that need millisecond precision.
 * - `track_summary` contains pre-computed stats so readers don't have to re-parse the track.
 *
 * ## Distance calculation
 * Haversine formula is used for all distance computations. Auto-paused points are included in
 * the point count but excluded from the distance sum (they represent stationary intervals).
 *
 * ## Pure function
 * Stateless [object] — no dependencies, no side effects.
 */
object IndexJsonBuilder {

    private val json = Json { prettyPrint = true; encodeDefaults = false }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Builds and returns the complete `index.json` string for [group].
     *
     * @param group       The [TripGroup] to export.
     * @param sessions    All sessions belonging to [group], in any order.
     * @param pointsBySession  Map from session ID → sorted list of [TrackPoint].
     * @param notesBySession   Map from session ID → list of [Note].
     * @param exportedAtMs     The timestamp to record in `exported_at` (epoch millis UTC).
     */
    fun build(
        group: TripGroup,
        sessions: List<Session>,
        pointsBySession: Map<String, List<TrackPoint>>,
        notesBySession: Map<String, List<Note>>,
        exportedAtMs: Long
    ): String {
        val dto = IndexJsonDto(
            schemaVersion = 1,
            exportedAt = formatIso8601(exportedAtMs),
            group = buildGroupDto(group, sessions, pointsBySession, notesBySession)
        )
        return json.encodeToString(IndexJsonDto.serializer(), dto)
    }

    // ------------------------------------------------------------------
    // DTO construction helpers
    // ------------------------------------------------------------------

    private fun buildGroupDto(
        group: TripGroup,
        sessions: List<Session>,
        pointsBySession: Map<String, List<TrackPoint>>,
        notesBySession: Map<String, List<Note>>
    ) = GroupDto(
        id        = group.id,
        name      = group.name,
        createdAt = formatIso8601(group.createdAt),
        updatedAt = formatIso8601(group.updatedAt),
        sessions  = sessions.map { session ->
            val points = pointsBySession[session.id] ?: emptyList()
            val notes  = notesBySession[session.id]  ?: emptyList()
            buildSessionDto(session, points, notes)
        }
    )

    private fun buildSessionDto(
        session: Session,
        points:  List<TrackPoint>,
        notes:   List<Note>
    ) = SessionDto(
        id          = session.id,
        name        = session.name,
        startTime   = formatIso8601(session.startTime),
        endTime     = session.endTime?.let { formatIso8601(it) },
        status      = session.status.name.lowercase(),
        trackSummary = buildTrackSummary(session, points),
        tracks      = points.map { buildTrackPointDto(it) },
        notes       = notes.map { buildNoteDto(it) }
    )

    private fun buildTrackSummary(session: Session, points: List<TrackPoint>): TrackSummaryDto {
        // Auto-paused points are counted but excluded from distance (they are stationary).
        val activePoints = points.filter { !it.isAutoPaused }

        val distanceMeters = activePoints
            .zipWithNext { a, b -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }
            .sum()

        // Duration: endTime - startTime if completed; otherwise 0 for in-progress/interrupted.
        val durationSeconds = session.endTime?.let { (it - session.startTime) / 1000L } ?: 0L

        return TrackSummaryDto(
            pointCount     = points.size,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds
        )
    }

    private fun buildTrackPointDto(pt: TrackPoint) = TrackPointDto(
        t    = pt.timestamp,
        lat  = pt.latitude,
        lng  = pt.longitude,
        alt  = pt.altitude,
        acc  = pt.accuracy,
        spd  = pt.speed,
        mode = pt.transportMode.name.lowercase()
    )

    private fun buildNoteDto(note: Note) = NoteDto(
        id              = note.id,
        type            = note.type.name.lowercase(),
        createdAt       = formatIso8601(note.createdAt),
        lat             = note.latitude,
        lng             = note.longitude,
        content         = note.content,
        // Voice note path is relative to the archive root so the desktop tool
        // can resolve it regardless of where the archive was unzipped.
        audioFile       = note.audioFilename?.let { "notes/$it" },
        durationSeconds = note.durationSeconds
    )

    // ------------------------------------------------------------------
    // Haversine distance
    // ------------------------------------------------------------------

    /**
     * Returns the great-circle distance in metres between two WGS-84 coordinates
     * using the haversine formula. Accurate to within ~0.5% for distances under 1000 km.
     *
     * @param lat1 Latitude of point A in decimal degrees.
     * @param lon1 Longitude of point A in decimal degrees.
     * @param lat2 Latitude of point B in decimal degrees.
     * @param lon2 Longitude of point B in decimal degrees.
     */
    internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusM = 6_371_000.0
        // Radians conversion: kotlin.math.PI is available in commonMain; Math.toRadians is Java-only.
        val dLat = (lat2 - lat1) * (PI / 180.0)
        val dLon = (lon2 - lon1) * (PI / 180.0)
        val lat1Rad = lat1 * (PI / 180.0)
        val lat2Rad = lat2 * (PI / 180.0)
        val a = sin(dLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        return earthRadiusM * 2 * asin(sqrt(a))
    }

    internal fun formatIso8601(epochMillis: Long): String =
        Instant.fromEpochMilliseconds(epochMillis).toString()

    // ------------------------------------------------------------------
    // Serializable DTOs — private to this object to prevent leaking schema
    // internals; only IndexJsonBuilder.build() is public.
    // ------------------------------------------------------------------

    @Serializable
    private data class IndexJsonDto(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("exported_at")    val exportedAt: String,
        val group: GroupDto
    )

    @Serializable
    private data class GroupDto(
        val id: String,
        val name: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
        val sessions: List<SessionDto>
    )

    @Serializable
    private data class SessionDto(
        val id: String,
        val name: String,
        @SerialName("start_time")    val startTime: String,
        // = null default is required for encodeDefaults = false to omit the key when null.
        // Without it, kotlinx.serialization encodes null as JSON `null` regardless of the setting.
        @SerialName("end_time")      val endTime: String? = null,
        val status: String,
        @SerialName("track_summary") val trackSummary: TrackSummaryDto,
        val tracks: List<TrackPointDto>,
        val notes: List<NoteDto>
    )

    @Serializable
    private data class TrackSummaryDto(
        @SerialName("point_count")      val pointCount: Int,
        @SerialName("distance_meters")  val distanceMeters: Double,
        @SerialName("duration_seconds") val durationSeconds: Long
    )

    @Serializable
    private data class TrackPointDto(
        val t: Long,
        val lat: Double,
        val lng: Double,
        val alt: Double? = null,
        val acc: Float,
        val spd: Float?  = null,
        val mode: String
    )

    @Serializable
    private data class NoteDto(
        val id: String,
        val type: String,
        @SerialName("created_at")       val createdAt: String,
        val lat: Double,
        val lng: Double,
        val content: String?        = null,
        @SerialName("audio_file")       val audioFile: String? = null,
        @SerialName("duration_seconds") val durationSeconds: Int? = null
    )
}
