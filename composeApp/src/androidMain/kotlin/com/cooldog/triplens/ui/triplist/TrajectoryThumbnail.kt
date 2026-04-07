package com.cooldog.triplens.ui.triplist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Draws a simple polyline trajectory from down-sampled GPS coordinates.
 *
 * The composable normalizes lat/lng into the canvas pixel space and draws a smooth path.
 * Designed as an isolated, replaceable component — swapping to a MapLibre async snapshot
 * in the future only requires changing the internals of this composable, not any call sites.
 *
 * @param points Down-sampled lat/lng pairs from [TripGroupItem.thumbnailPoints].
 *               Empty list renders a blank canvas (no "No GPS data" text to keep it minimal).
 * @param modifier Modifier for sizing and positioning.
 */
@Composable
fun TrajectoryThumbnail(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.fillMaxSize()) {
        if (points.size < 2) return@Canvas

        // Compute bounding box of all points.
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLng = points.minOf { it.second }
        val maxLng = points.maxOf { it.second }

        val latRange = (maxLat - minLat).coerceAtLeast(0.0001)  // avoid division by zero
        val lngRange = (maxLng - minLng).coerceAtLeast(0.0001)

        // Add padding so the polyline doesn't touch the canvas edges.
        val paddingPx = 8f
        val drawWidth = size.width - paddingPx * 2
        val drawHeight = size.height - paddingPx * 2

        // Normalize points to canvas pixel coordinates.
        // Latitude is inverted: higher lat = higher on screen (lower y pixel value).
        val pixelPoints = points.map { (lat, lng) ->
            val x = paddingPx + ((lng - minLng) / lngRange * drawWidth).toFloat()
            val y = paddingPx + ((1.0 - (lat - minLat) / latRange) * drawHeight).toFloat()
            Offset(x, y)
        }

        // Draw the polyline path.
        val path = Path().apply {
            moveTo(pixelPoints.first().x, pixelPoints.first().y)
            for (i in 1 until pixelPoints.size) {
                lineTo(pixelPoints[i].x, pixelPoints[i].y)
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 3f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
