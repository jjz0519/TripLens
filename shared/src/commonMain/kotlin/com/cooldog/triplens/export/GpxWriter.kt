package com.cooldog.triplens.export

import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.TrackPoint
import kotlinx.datetime.Instant

/**
 * Serialises a single [Session] and its [TrackPoint] list to a GPX 1.1 XML string.
 *
 * ## Format
 * Standard GPX 1.1 with a custom `triplens` namespace for speed, accuracy, and transport mode
 * extensions. Extensions are nested inside each `<trkpt>` as:
 * ```xml
 * <extensions>
 *   <triplens:speed>1.5</triplens:speed>       <!-- m/s; omitted when null -->
 *   <triplens:accuracy>8.5</triplens:accuracy>  <!-- metres horizontal -->
 *   <triplens:mode>walking</triplens:mode>       <!-- lowercase TransportMode name -->
 * </extensions>
 * ```
 *
 * ## Nullable fields
 * - `<ele>` is omitted entirely when [TrackPoint.altitude] is null.
 * - `<triplens:speed>` is omitted when [TrackPoint.speed] is null.
 *
 * ## Pure function
 * This is a stateless [object] — no dependencies, no side effects. It can be called directly
 * from [ExportUseCase] without Koin registration.
 */
object GpxWriter {

    private const val GPX_NAMESPACE     = "http://www.topografix.com/GPX/1/1"
    private const val TRIPLENS_NS_URI   = "https://triplens.app/gpx/extensions/v1"
    private const val GPX_SCHEMA_LOC    =
        "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"

    /**
     * Produces a complete GPX 1.1 document string for [session] and [points].
     *
     * @param session The session whose metadata populates `<metadata>` and `<trk name>`.
     * @param points  Track points in chronological order. May be empty.
     */
    fun write(session: Session, points: List<TrackPoint>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<gpx version="1.1" creator="TripLens"""")
        append(""" xmlns="$GPX_NAMESPACE"""")
        append(""" xmlns:triplens="$TRIPLENS_NS_URI"""")
        append(""" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
        appendLine(""" xsi:schemaLocation="$GPX_SCHEMA_LOC">""")

        // --- Metadata ---
        appendLine("  <metadata>")
        appendLine("    <name>${escapeXml(session.name)}</name>")
        appendLine("    <time>${formatIso8601(session.startTime)}</time>")
        appendLine("  </metadata>")

        // --- Track ---
        appendLine("  <trk>")
        appendLine("    <name>${escapeXml(session.name)}</name>")
        appendLine("    <trkseg>")
        for (pt in points) {
            append("      <trkpt")
            append(""" lat="${pt.latitude}"""")
            appendLine(""" lon="${pt.longitude}">""")
            if (pt.altitude != null) {
                appendLine("        <ele>${pt.altitude}</ele>")
            }
            appendLine("        <time>${formatIso8601(pt.timestamp)}</time>")
            appendLine("        <extensions>")
            if (pt.speed != null) {
                // Speed stored as m/s; retain full precision for downstream tools.
                appendLine("          <triplens:speed>${pt.speed}</triplens:speed>")
            }
            appendLine("          <triplens:accuracy>${pt.accuracy}</triplens:accuracy>")
            // Transport mode is already lowercase in the DB; use the enum's lowercase name for
            // explicitness so this doesn't break if the model ever changes casing.
            appendLine("          <triplens:mode>${pt.transportMode.name.lowercase()}</triplens:mode>")
            appendLine("        </extensions>")
            appendLine("      </trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        append("</gpx>")
    }

    // --- Helpers ---

    /**
     * Formats an epoch-millisecond timestamp as an ISO 8601 UTC string
     * (e.g. `"2024-01-15T09:00:05Z"`). Uses [kotlinx.datetime.Instant] for
     * cross-platform correctness without a timezone database.
     */
    internal fun formatIso8601(epochMillis: Long): String =
        Instant.fromEpochMilliseconds(epochMillis).toString()

    /**
     * Escapes the five XML special characters in attribute values and text content.
     * GPX element names (session names) can contain user-supplied text.
     */
    internal fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
