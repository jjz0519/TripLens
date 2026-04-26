package com.cooldog.triplens.ui.tripdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooldog.triplens.R
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.ui.common.ExportState
import com.cooldog.triplens.ui.common.RenameDialog
import com.cooldog.triplens.ui.common.formatDistance
import com.cooldog.triplens.ui.common.formatDuration
import com.cooldog.triplens.ui.common.startShareFileIntent
import com.cooldog.triplens.ui.theme.BiophilicColors
import com.cooldog.triplens.ui.theme.InstrumentSerifFamily
import com.cooldog.triplens.ui.theme.LocalBiophilicColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TripGroup Detail screen — biophilic redesign (Task 26).
 *
 * Layout:
 * ```
 * Scaffold(bg) {
 *   Column {
 *     TopBar: back button | trip name + date range | export button/spinner
 *     StatPillsRow: Sessions / Distance / Duration
 *     "Sessions" section label
 *     LazyColumn { SessionCard × N; Spacer(20dp) }
 *   }
 * }
 * ```
 *
 * All functional behavior from the original implementation is preserved:
 * - RenameDialog triggered from the three-dot menu on each SessionCard
 * - One-shot events collected in LaunchedEffect (snackbar + share-sheet)
 * - ExportState.InProgress spinner replaces the export icon in the top bar
 *
 * @param viewModel      The [TripDetailViewModel] providing state and handling actions.
 * @param onSessionClick Called when a session card is tapped — navigates to SessionReviewRoute.
 * @param onBack         Called when the back button is tapped.
 */
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    onSessionClick: (sessionId: String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState     by viewModel.uiState.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context     = LocalContext.current
    val bio         = LocalBiophilicColors.current

    // Dialog state — which session is being renamed.
    var renameTarget by remember { mutableStateOf<SessionItem?>(null) }

    // Collect one-shot events: snackbar messages and share-sheet triggers.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TripDetailViewModel.Event.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is TripDetailViewModel.Event.ShareFile -> {
                    context.startShareFileIntent(event.path)
                }
            }
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────
    renameTarget?.let { item ->
        RenameDialog(
            currentName = item.name,
            title       = stringResource(R.string.trip_detail_rename_session_title),
            onConfirm   = { newName ->
                viewModel.onRenameSession(item.id, newName)
                renameTarget = null
            },
            onDismiss   = { renameTarget = null },
        )
    }

    // ── Screen layout ─────────────────────────────────────────────────────────
    Scaffold(
        containerColor = bio.bg,
        snackbarHost   = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val state = uiState) {
            // ── Loading ───────────────────────────────────────────────────────
            is TripDetailViewModel.UiState.Loading -> {
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = bio.moss)
                }
            }

            // ── Error ─────────────────────────────────────────────────────────
            is TripDetailViewModel.UiState.Error -> {
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text     = state.message,
                        color    = bio.ink2,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ── Loaded ────────────────────────────────────────────────────────
            is TripDetailViewModel.UiState.Loaded -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    // ── Top bar ───────────────────────────────────────────────
                    DetailTopBar(
                        bio         = bio,
                        groupName   = state.groupName,
                        sessions    = state.sessions,
                        exportState = exportState,
                        onBack      = onBack,
                        onExport    = { viewModel.onExportGroup() },
                    )

                    // ── Stat pills ────────────────────────────────────────────
                    DetailStatPillsRow(
                        bio           = bio,
                        sessionCount  = state.sessionCount,
                        totalDistance = state.totalDistanceMeters,
                        totalDuration = state.totalDurationSeconds,
                    )

                    // ── "Sessions" section label ──────────────────────────────
                    Text(
                        text       = "Sessions",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = bio.ink2,
                        modifier   = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
                    )

                    // ── Session cards ─────────────────────────────────────────
                    LazyColumn(
                        modifier              = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding        = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 20.dp,
                        ),
                        verticalArrangement   = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.sessions, key = { it.id }) { session ->
                            SessionCard(
                                bio      = bio,
                                item     = session,
                                onClick  = { onSessionClick(session.id) },
                                onRename = { renameTarget = session },
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Biophilic top bar for the detail screen.
 *
 * Layout (horizontal):
 * - 40dp circle back button (ArrowBack icon, ink2)
 * - Column(weight=1) with date range label (MMM yyyy) + group name (Instrument Serif 22sp)
 * - If export in progress: 20dp CircularProgressIndicator (moss)
 *   Else: 40dp circle share button (Share icon, ink2)
 *
 * The date range label shows the first session's start date formatted as "MMM yyyy"
 * (e.g. "Apr 2026") or an empty string if no sessions are present.
 */
@Composable
private fun DetailTopBar(
    bio:         BiophilicColors,
    groupName:   String,
    sessions:    List<SessionItem>,
    exportState: ExportState,
    onBack:      () -> Unit,
    onExport:    () -> Unit,
) {
    // Format the first session's start time as "MMM yyyy" (e.g. "Apr 2026").
    // SimpleDateFormat is not thread-safe, but we're on the main thread here.
    val dateLabel = remember(sessions) {
        if (sessions.isEmpty()) {
            ""
        } else {
            SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(sessions.first().startTime))
        }
    }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back button — 40dp circle with surface bg and line2 border.
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(bio.surface)
                .border(1.dp, bio.line2, CircleShape),
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint               = bio.ink2,
                modifier           = Modifier.size(20.dp),
            )
        }

        // Title column: date range + group name
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        ) {
            if (dateLabel.isNotEmpty()) {
                Text(
                    text          = dateLabel.uppercase(Locale.getDefault()),
                    fontSize      = 11.sp,
                    letterSpacing = 0.5.sp,
                    fontWeight    = FontWeight.Medium,
                    color         = bio.ink3,
                )
            }
            Text(
                text       = groupName,
                fontFamily = InstrumentSerifFamily,
                fontSize   = 22.sp,
                color      = bio.ink,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }

        // Export action: spinner while in progress, share button otherwise.
        if (exportState is ExportState.InProgress) {
            // Keep same 40dp footprint so the bar height doesn't shift during export.
            Box(
                modifier         = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color       = bio.moss,
                )
            }
        } else {
            IconButton(
                onClick  = onExport,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bio.surface)
                    .border(1.dp, bio.line2, CircleShape),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.action_export),
                    tint               = bio.ink2,
                    modifier           = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stat pills
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Three equal-width stat pills showing aggregate trip statistics.
 *
 * Pills: Sessions count / Distance in km (1 decimal) / Duration (formatDuration).
 * Uses [StatPillSmall] for each pill.
 */
@Composable
private fun DetailStatPillsRow(
    bio:           BiophilicColors,
    sessionCount:  Int,
    totalDistance: Double,
    totalDuration: Long,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatPillSmall(
            bio      = bio,
            value    = sessionCount.toString(),
            label    = "SESSIONS",
            modifier = Modifier.weight(1f),
        )
        StatPillSmall(
            bio      = bio,
            // Distance formatted to 1 decimal km, consistent with TripListScreen convention.
            value    = "${"%.1f".format(totalDistance / 1000.0)} km",
            label    = "DISTANCE",
            modifier = Modifier.weight(1f),
        )
        StatPillSmall(
            bio      = bio,
            value    = formatDuration(totalDuration),
            label    = "DURATION",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A single stat pill — surface background, line2 border, 16dp radius.
 *
 * @param value The primary metric text (Instrument Serif 18sp, ink).
 * @param label The uppercase descriptor below the value (10sp, ink3, SemiBold, 0.3sp tracking).
 */
@Composable
private fun StatPillSmall(
    bio:      BiophilicColors,
    value:    String,
    label:    String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text       = value,
            fontFamily = InstrumentSerifFamily,
            fontSize   = 18.sp,
            color      = bio.ink,
        )
        Text(
            text          = label,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            color         = bio.ink3,
            modifier      = Modifier.padding(top = 3.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Biophilic session card — replaces the original [SessionRow].
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────┐
 * │ Session name              [⋮]   │
 * │ Apr 2, 10:30 – 12:45 · 2h 15m  │
 * │ ● 12.3 km  ● 2.1 km            │  (transport breakdown dots)
 * └──────────────────────────────────┘
 * ```
 *
 * Styling: surface bg, line2 border, 20dp radius, 16dp padding.
 * Three-dot menu contains a single "Rename" action (preserved from original).
 *
 * @param item     The [SessionItem] to display.
 * @param onClick  Called when the card body is tapped.
 * @param onRename Called when the user selects Rename from the menu.
 */
@Composable
private fun SessionCard(
    bio:      BiophilicColors,
    item:     SessionItem,
    onClick:  () -> Unit,
    onRename: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // ── Content column ────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                // Session name
                Text(
                    text       = item.name,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = bio.ink,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )

                // Date/time range + duration on one line
                Text(
                    text     = "${item.dateTimeRange} · ${formatDuration(item.durationSeconds)}",
                    fontSize = 12.sp,
                    color    = bio.ink2,
                    modifier = Modifier.padding(top = 6.dp),
                )

                // Transport breakdown: colored dot + distance per mode
                if (item.transportBreakdown.isNotEmpty()) {
                    Row(
                        modifier              = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        item.transportBreakdown.forEach { seg ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                // Color-coded 8dp circle representing the transport mode.
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(segmentColor(seg.mode, bio)),
                                )
                                Text(
                                    text     = formatDistance(seg.distanceMeters),
                                    fontSize = 11.sp,
                                    color    = bio.ink3,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            // ── Three-dot menu ────────────────────────────────────────────────
            Box {
                IconButton(
                    onClick  = { showMenu = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.trip_list_more_options),
                        tint               = bio.ink3,
                        modifier           = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded         = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text        = { Text(stringResource(R.string.action_rename)) },
                        onClick     = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transport mode color mapping
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps a [TransportMode] to a biophilic dot color for the session breakdown row.
 *
 * Color assignments mirror the semantic meaning of each mode:
 * - Walking    → moss (active, green)
 * - Stationary → sun (paused, warm amber)
 * - Cycling    → clay (earthy, moderate effort)
 * - Driving    → mossDeep (mechanical, deeper green)
 * - FastTransit → ink2 (neutral, highest speed)
 */
private fun segmentColor(mode: TransportMode, bio: BiophilicColors): Color = when (mode) {
    TransportMode.WALKING      -> bio.moss
    TransportMode.STATIONARY   -> bio.sun
    TransportMode.CYCLING      -> bio.clay
    TransportMode.DRIVING      -> bio.mossDeep
    TransportMode.FAST_TRANSIT -> bio.ink2
}
