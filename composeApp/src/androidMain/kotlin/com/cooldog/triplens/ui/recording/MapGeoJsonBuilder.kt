package com.cooldog.triplens.ui.recording

import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Converts a list of [TrackPoint]s into a MapLibre GeoJSON [FeatureCollection] suitable for
 * use with a data-driven [org.maplibre.android.style.layers.LineLayer].
 *
 * ## Algorithm
 * 1. Auto-paused points are excluded so gaps appear in the polyline during stationary periods.
 * 2. Remaining points are grouped into contiguous runs of the same [TransportMode].
 * 3. Each run with ≥ 2 points becomes a GeoJSON [Feature] containing a [LineString].
 * 4. Each Feature carries a `"color"` string property read by the data-driven LineLayer,
 *    eliminating the need for 5 separate layer/source pairs.
 *
 * ## Reuse
 * This object has no Android context dependency — it only uses MapLibre's GeoJSON model
 * classes. Task 15 (SessionReviewScreen) reuses it directly.
 */
object MapGeoJsonBuilder {

    // Material-inspired color palette keyed to each transport mode.
    private fun TransportMode.lineColor(): String = when (this) {
        TransportMode.STATIONARY   -> "#9E9E9E"  // gray    — parked / still
        TransportMode.WALKING      -> "#4CAF50"  // green   — on foot
        TransportMode.CYCLING      -> "#FF9800"  // orange  — bike
        TransportMode.DRIVING      -> "#2196F3"  // blue    — car / motorbike
        TransportMode.FAST_TRANSIT -> "#9C27B0"  // purple  — train / metro
    }

    /**
     * Builds a [FeatureCollection] where each [Feature] is a same-mode [LineString] segment.
     *
     * @param points Full ordered list of [TrackPoint]s for the session, newest last.
     * @return A FeatureCollection ready to pass to `GeoJsonSource.setGeoJson()`.
     *         Returns an empty collection if [points] is empty or all points are auto-paused.
     */
    fun buildRouteFeatureCollection(points: List<TrackPoint>): FeatureCollection {
        // Exclude auto-paused points so the polyline has visual gaps during long stops.
        val active = points.filter { !it.isAutoPaused }
        if (active.size < 2) return FeatureCollection.fromFeatures(emptyList())

        val features = mutableListOf<Feature>()
        var segmentStart = 0
        var currentMode = active[0].transportMode

        for (i in 1..active.size) {
            val endOfList   = i == active.size
            val modeChanged = !endOfList && active[i].transportMode != currentMode

            if (endOfList || modeChanged) {
                // Segments with < 2 points cannot form a LineString — skip.
                val seg = active.subList(segmentStart, i)
                if (seg.size >= 2) {
                    val coords = seg.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val feature = Feature.fromGeometry(LineString.fromLngLats(coords))
                    feature.addStringProperty("color", currentMode.lineColor())
                    features.add(feature)
                }
                if (!endOfList) {
                    segmentStart = i
                    currentMode  = active[i].transportMode
                }
            }
        }

        return FeatureCollection.fromFeatures(features)
    }
}
