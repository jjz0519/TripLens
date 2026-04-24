package com.cooldog.triplens.ui.recording

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cooldog.triplens.ui.theme.BiophilicColors
import com.cooldog.triplens.ui.theme.InstrumentSerifFamily
import com.cooldog.triplens.ui.theme.LocalBiophilicColors

/**
 * Full-screen overlay composable shown when the recording screen is in
 * [RecordingViewModel.UiState.Idle] or [RecordingViewModel.UiState.StartingSession].
 *
 * Layout (all children are positioned inside a [Box] on top of the MapLibre map):
 * ```
 * ┌──────────────────────────────────────────┐
 * │  READY WHEN YOU ARE                      │  ← header strip (solid bio.bg)
 * │  Begin a new trip                        │
 * ├──────────────────────────────────────────┤
 * │  [map visible here]                      │
 * │  ┌─────────────────────────────────────┐ │
 * │  │ ◉ Current location  · GPS ready   ⊞ │ │  ← GPS card (top of map area)
 * │  └─────────────────────────────────────┘ │
 * │                                     [⊕] │  ← re-center button (right edge)
 * │  ┌─────────────────────────────────────┐ │
 * │  │ ◌  Tap to start  Route, photos… [▶] │ │  ← start panel (floating bottom glass)
 * │  └─────────────────────────────────────┘ │
 * ├──────────────────────────────────────────┤
 * │  🌱 Auto-pause when you stand still      │  ← footer tip (solid bio.bg)
 * └──────────────────────────────────────────┘
 * ```
 *
 * This composable is rendered as an OVERLAY inside the same Box that holds the MapLibre
 * [AndroidView]. The MapView stays at composition slot 0 in that Box; this overlay is slot 1,
 * so Compose never tears down the GL context across Idle ↔ ActiveRecording transitions.
 *
 * The header and footer strips use a solid [BiophilicColors.bg] background so they are legible
 * regardless of the map tiles beneath. The GPS card and start panel use semi-transparent
 * [BiophilicColors.surface] so the map remains visible through them.
 */
@Composable
internal fun RecordingIdleContent(
    uiState: RecordingViewModel.UiState,
    onStartTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bio = LocalBiophilicColors.current

    Box(modifier = modifier) {

        // ── Header strip (solid bg, top of screen) ──────────────────────────────
        // Solid background ensures the title is always legible over any map tiles.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(bio.bg)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                "READY WHEN YOU ARE",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                color = bio.ink3,
            )
            Text(
                "Begin a new trip",
                fontFamily = InstrumentSerifFamily,
                fontSize = 28.sp,
                color = bio.ink,
                // Tight tracking suits the display-weight Instrument Serif at large sizes.
                letterSpacing = (-0.015).em,
                lineHeight = 30.sp,
            )
        }

        // ── GPS card overlay (top-left of map area, below header) ───────────────
        // The header is ~68 dp tall (title + subtitle + vertical padding).
        // padding(top = 80.dp) gives an 8 dp gap between header bottom and card top.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, end = 12.dp, top = 80.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(bio.surface.copy(alpha = 0.92f))
                .border(1.dp, bio.line2, RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Location icon badge
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bio.mossPale),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.LocationOn, contentDescription = null,
                    tint = bio.mossDeep,
                    modifier = Modifier.size(16.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Current location",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = bio.ink,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Green status dot — indicates GPS is ready/active
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(bio.moss),
                    )
                    Text("GPS ready", fontSize = 11.sp, color = bio.ink3)
                }
            }

            // Map layers toggle button (currently a stub; wired up in a later task)
            IconButton(
                onClick = { },
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bio.bg2),
            ) {
                Icon(
                    Icons.Outlined.Layers, contentDescription = "Map layers",
                    tint = bio.ink2,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // ── Re-center button (right side, above bottom panel) ───────────────────
        // Tapping this will pan the camera back to the user's GPS dot.
        // Functional wiring deferred to the map-interaction task.
        IconButton(
            onClick = { },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 150.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bio.surface)
                .border(1.dp, bio.line2, RoundedCornerShape(12.dp)),
        ) {
            Icon(
                Icons.Outlined.MyLocation, contentDescription = "Re-center map",
                tint = bio.mossDeep,
                modifier = Modifier.size(18.dp),
            )
        }

        // ── Start panel (floating bottom glass) ─────────────────────────────────
        // 68 dp bottom padding clears the bottom navigation bar (Task 21 height).
        // Semi-transparent surface lets the map show through while keeping text readable.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 68.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(bio.surface.copy(alpha = 0.96f))
                .border(1.dp, bio.line2, RoundedCornerShape(22.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniOrb(bio)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Tap to start",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = bio.ink,
                )
                Text(
                    "Route, photos, notes — woven together.",
                    fontSize = 11.sp,
                    color = bio.ink3,
                    lineHeight = 15.sp,
                )
            }

            Button(
                onClick = onStartTapped,
                enabled = uiState == RecordingViewModel.UiState.Idle,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = bio.mossDeep,
                    contentColor   = bio.bg,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (uiState == RecordingViewModel.UiState.StartingSession) {
                    // Show a loading spinner while the session is being initialised
                    // (DB write, service bind, permission check happen on this transition).
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = bio.bg,
                        strokeWidth = 2.dp,
                    )
                } else {
                    // Record dot + label. The red dot mirrors the standard recording-indicator
                    // language users recognise from camera apps.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(bio.recordRed),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Start", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Footer tip (solid bg strip at very bottom) ──────────────────────────
        // Sits below the start panel. Communicates auto-pause behaviour upfront so
        // users understand why recording might pause during stationary periods.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(bio.bg)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bio.mossPale),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Eco, contentDescription = null,
                    tint = bio.mossDeep,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = bio.ink2, fontWeight = FontWeight.Medium)) {
                        append("Auto-pause")
                    }
                    withStyle(SpanStyle(color = bio.ink3)) {
                        append(" when you stand still — saves battery.")
                    }
                },
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/**
 * Animated pulsing orb shown in the start panel.
 *
 * Three concentric circles pulse at slightly different rates to create an organic, breathing
 * effect. The two outer rings use mossPale to stay subtle; the core uses moss for identity.
 * The white centre dot adds a highlight that reads as a reflection/glow.
 *
 * Radii are intentionally kept small (10–21 dp) so the orb fits neatly inside the 44 dp Canvas
 * without clipping, and the pulsing range (18→21 dp outer, 14→16 dp mid) is subtle enough not
 * to distract from the Start button beside it.
 */
@Composable
private fun MiniOrb(bio: BiophilicColors) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // Outer ring: slow, wide pulse (3.5 s)
    val r1 by infiniteTransition.animateFloat(
        initialValue = 18f,
        targetValue  = 21f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "r1",
    )
    // Mid ring: slightly faster (3 s)
    val r2 by infiniteTransition.animateFloat(
        initialValue = 14f,
        targetValue  = 16f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "r2",
    )

    Canvas(modifier = Modifier.size(44.dp)) {
        val c = center
        drawCircle(bio.mossPale.copy(alpha = 0.5f), radius = r1.dp.toPx(), center = c)
        drawCircle(bio.mossPale2,                   radius = r2.dp.toPx(), center = c)
        drawCircle(bio.moss.copy(alpha = 0.95f),    radius = 10.dp.toPx(), center = c)
        // White highlight dot at the core
        drawCircle(bio.bg,                          radius = 4.dp.toPx(),  center = c)
    }
}
