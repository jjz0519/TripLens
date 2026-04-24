package com.cooldog.triplens.ui.recording

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.ui.theme.BiophilicColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular progress ring displayed in the recording top bar.
 *
 * Visual layers (bottom to top):
 * 1. Track ring: faint background circle (bio.line2) that shows the full 1-hour span.
 * 2. Elapsed arc: clockwise arc from 12 o'clock, green (recording) or amber (paused),
 *    capped at 1 hour (fraction = elapsedSeconds / 3600).
 * 3. Moment petals: up to 12 small dots orbiting just outside the track ring, one per
 *    captured moment. Evenly spaced regardless of how many there are (always 12 slots).
 * 4. Inner indicator: a soft-glow disc + solid centre dot; red when recording, amber when
 *    paused — mirrors the arc colour for immediate visual consistency.
 *
 * @param elapsedSeconds  Session elapsed time in seconds; drives the arc sweep (0–3600).
 * @param momentCount     Number of captured moments; controls petal count (capped at 12).
 * @param paused          When true, arc and dot colour switch from recordRed/mossDeep to sun.
 * @param bio             Active biophilic colour palette from [LocalBiophilicColors].
 * @param modifier        Standard Compose modifier; caller supplies the size (e.g. 60.dp).
 */
@Composable
fun ProgressRing(
    elapsedSeconds: Long,
    momentCount: Int,
    paused: Boolean,
    bio: BiophilicColors,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val canvasSize = minOf(size.width, size.height)
        val center = Offset(size.width / 2, size.height / 2)
        val radius = canvasSize * 0.4f
        val strokeW = 3.dp.toPx()

        // Track ring background — subtle guide that shows the full 1-hour arc span.
        drawCircle(bio.line2, radius = radius, center = center, style = Stroke(strokeW))

        // Elapsed arc (clockwise from 12 o'clock, max 1 hour).
        // startAngle = -90° to begin at top-centre rather than the default 3 o'clock.
        val fraction = (elapsedSeconds / 3600f).coerceIn(0f, 1f)
        val sweepAngle = fraction * 360f
        val arcColor = if (paused) bio.sun else bio.mossDeep
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(strokeW, cap = StrokeCap.Round),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
        )

        // Moment petals — up to 12 dots orbiting just outside the track ring.
        // Each dot occupies one of 12 evenly-spaced slots, filling left-to-right from 12 o'clock.
        val petals = minOf(momentCount, 12)
        val petalRadius = 1.8.dp.toPx()
        val orbitR = radius + 6.dp.toPx()
        for (i in 0 until petals) {
            val angle = (i.toDouble() / 12.0) * 2 * PI - PI / 2
            val px = (center.x + cos(angle) * orbitR).toFloat()
            val py = (center.y + sin(angle) * orbitR).toFloat()
            drawCircle(bio.clay, radius = petalRadius, center = Offset(px, py))
        }

        // Inner dot — soft glow halo + solid centre pin.
        // Colour mirrors the arc so paused vs. recording state is immediately readable.
        val dotColor = if (paused) bio.sun else bio.recordRed
        drawCircle(dotColor.copy(alpha = 0.12f), radius = 14.dp.toPx(), center = center)
        drawCircle(dotColor, radius = 6.dp.toPx(), center = center)
    }
}
