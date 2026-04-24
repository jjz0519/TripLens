package com.cooldog.triplens.ui.triplist

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.ui.theme.BiophilicColors

/**
 * BiophilicMiniMap — stylized canvas mini-map for a trip card.
 *
 * Renders a small decorative map thumbnail using the biophilic design tokens:
 * - Solid land background (bio.mapLand)
 * - A water blob (quadratic bezier, top-left) for visual interest
 * - A route polyline in bio.mossDeep (2.5dp, round caps/joins)
 * - Start dot (bio.moss) and end dot (bio.clay) at 4dp radius
 * - Up to 4 evenly-spaced moment dots (bio.sun inner + white ring)
 *
 * Normalization maps the bounding box of lat/lng to 10%-90% of canvas width/height
 * so the route never touches the edges. Latitude is inverted (higher lat → lower y).
 *
 * Edge cases:
 * - Fewer than 2 points: draws only the decorative water blob and background.
 * - Single-point list: treated as <2 — no route drawn.
 *
 * @param bio         BiophilicColors token set from LocalBiophilicColors.current.
 * @param trackPoints Down-sampled (lat, lng) pairs from TripGroupItem.thumbnailPoints.
 * @param momentCount Total photo + video + note count; used to decide how many moment dots to show.
 * @param modifier    Modifier for sizing and positioning.
 */
@Composable
fun BiophilicMiniMap(
    bio: BiophilicColors,
    trackPoints: List<Pair<Double, Double>>,
    momentCount: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // ── Background ────────────────────────────────────────────────────────
        drawRect(color = bio.mapLand)

        // ── Decorative water blob (top-left, quadratic bezier) ────────────────
        // A simple organic curved shape suggestive of a lake or coastline.
        val waterColor = bio.mapWater.copy(alpha = 0.6f)
        val blobPath = Path().apply {
            moveTo(0f, 0f)
            quadraticTo(size.width * 0.35f, size.height * 0.05f, size.width * 0.28f, size.height * 0.32f)
            quadraticTo(size.width * 0.12f, size.height * 0.38f, 0f, size.height * 0.28f)
            close()
        }
        drawPath(blobPath, color = waterColor)

        // ── Route polyline ────────────────────────────────────────────────────
        // Need at least 2 points to draw a meaningful route.
        if (trackPoints.size < 2) return@Canvas

        val minLat = trackPoints.minOf { it.first }
        val maxLat = trackPoints.maxOf { it.first }
        val minLng = trackPoints.minOf { it.second }
        val maxLng = trackPoints.maxOf { it.second }

        // Avoid division by zero if all points share a coordinate.
        val latRange = (maxLat - minLat).coerceAtLeast(0.0001)
        val lngRange = (maxLng - minLng).coerceAtLeast(0.0001)

        // Constrain the route to 10%–90% of the canvas in both axes so it never
        // bleeds into the visual border area.
        val padFraction = 0.10f
        val drawLeft  = size.width  * padFraction
        val drawTop   = size.height * padFraction
        val drawWidth = size.width  * (1f - 2 * padFraction)
        val drawHeight = size.height * (1f - 2 * padFraction)

        // Latitude is inverted: higher lat value → lower y pixel (higher on screen).
        val pixelPoints = trackPoints.map { (lat, lng) ->
            val x = drawLeft + ((lng - minLng) / lngRange * drawWidth).toFloat()
            val y = drawTop  + ((1.0 - (lat - minLat) / latRange) * drawHeight).toFloat()
            Offset(x, y)
        }

        val routePath = Path().apply {
            moveTo(pixelPoints.first().x, pixelPoints.first().y)
            for (i in 1 until pixelPoints.size) {
                lineTo(pixelPoints[i].x, pixelPoints[i].y)
            }
        }

        drawPath(
            path = routePath,
            color = bio.mossDeep,
            style = Stroke(
                width  = 2.5.dp.toPx(),
                cap    = StrokeCap.Round,
                join   = StrokeJoin.Round,
            ),
        )

        // ── Start dot (bio.moss) ──────────────────────────────────────────────
        drawCircle(
            color  = bio.moss,
            radius = 4.dp.toPx(),
            center = pixelPoints.first(),
        )

        // ── End dot (bio.clay) ───────────────────────────────────────────────
        drawCircle(
            color  = bio.clay,
            radius = 4.dp.toPx(),
            center = pixelPoints.last(),
        )

        // ── Moment dots (up to 4, evenly spaced along the route) ─────────────
        // Only draw when there are actual moments to represent.
        if (momentCount > 0 && pixelPoints.size >= 2) {
            val dotCount = minOf(momentCount, 4)
            // Pick evenly-spaced indices along the pixel points list, excluding
            // the first and last positions (those are start/end dots).
            val innerPoints = pixelPoints.drop(1).dropLast(1)
            if (innerPoints.isNotEmpty()) {
                val step = (innerPoints.size.toFloat() / (dotCount + 1)).coerceAtLeast(1f)
                repeat(dotCount) { i ->
                    val idx = ((i + 1) * step).toInt().coerceAtMost(innerPoints.lastIndex)
                    val center = innerPoints[idx]
                    // Draw filled sun circle first so the white ring is visible on top.
                    drawCircle(bio.sun, radius = 2.5.dp.toPx(), center = center)
                    // White stroke ring on top — drawn second so it is not obscured.
                    drawCircle(
                        Color.White,
                        radius = 2.5.dp.toPx(),
                        center = center,
                        style  = Stroke(1.dp.toPx()),
                    )
                }
            }
        }
    }
}
