package com.cooldog.triplens.ui.triplist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooldog.triplens.R
import com.cooldog.triplens.ui.common.DeleteConfirmDialog
import com.cooldog.triplens.ui.common.ExportState
import com.cooldog.triplens.ui.common.RenameDialog
import com.cooldog.triplens.ui.common.startShareFileIntent
import com.cooldog.triplens.ui.theme.BiophilicColors
import com.cooldog.triplens.ui.theme.InstrumentSerifFamily
import com.cooldog.triplens.ui.theme.LocalBiophilicColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas as FoundationCanvas

/**
 * Trip List screen — biophilic redesign (Task 22).
 *
 * Layout:
 *  - LazyColumn with:
 *    1. Editorial header: organic blob + date + title + search button
 *    2. Stat pills row (Trips / Distance / Moments) computed from loaded data
 *    3. Visual-only filter row ("Recent" label + "All" / "This week" chips)
 *    4. Trip cards (BiophilicMiniMap + text content + three-dot menu)
 *
 * All dialog state (rename, delete) and event handling (snackbar, share) are preserved
 * exactly from the previous implementation.
 *
 * @param viewModel  The [TripListViewModel] providing state and handling actions.
 * @param onGroupClick Called when a card is tapped — navigates to TripDetailRoute(groupId).
 */
@Composable
fun TripListScreen(
    viewModel: TripListViewModel,
    onGroupClick: (groupId: String) -> Unit,
) {
    val uiState     by viewModel.uiState.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context     = LocalContext.current
    val bio         = LocalBiophilicColors.current

    // Dialog state — which group is being renamed or deleted.
    var renameTarget by remember { mutableStateOf<TripGroupItem?>(null) }
    var deleteTarget by remember { mutableStateOf<TripGroupItem?>(null) }

    // Collect one-shot events: snackbar messages and share-sheet triggers.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TripListViewModel.Event.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is TripListViewModel.Event.ShareFile    -> context.startShareFileIntent(event.path)
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    renameTarget?.let { item ->
        RenameDialog(
            currentName = item.name,
            title       = stringResource(R.string.trip_list_rename_title),
            onConfirm   = { newName ->
                viewModel.onRenameGroup(item.id, newName)
                renameTarget = null
            },
            onDismiss   = { renameTarget = null },
        )
    }

    deleteTarget?.let { item ->
        DeleteConfirmDialog(
            itemName  = item.name,
            onConfirm = {
                viewModel.onDeleteGroup(item.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // ── Scaffold provides snackbar hosting and correct inset padding ─────────
    Scaffold(
        containerColor = bio.bg,
        snackbarHost   = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val state = uiState) {
            // ── Loading ───────────────────────────────────────────────────────
            is TripListViewModel.UiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = bio.moss)
                }
            }

            // ── Error ─────────────────────────────────────────────────────────
            is TripListViewModel.UiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text     = state.message,
                        color    = bio.recordRed,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ── Loaded ────────────────────────────────────────────────────────
            is TripListViewModel.UiState.Loaded -> {
                val groups = state.groups

                LazyColumn(
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top    = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    // ── 1. Editorial header ───────────────────────────────────
                    item {
                        BiophilicHeader(bio = bio)
                    }

                    // ── 2. Stat pills ─────────────────────────────────────────
                    item {
                        Spacer(Modifier.height(16.dp))
                        StatPillsRow(bio = bio, groups = groups)
                    }

                    // ── 3. Filter row (visual-only) ───────────────────────────
                    item {
                        Spacer(Modifier.height(12.dp))
                        FilterRow(bio = bio)
                    }

                    if (groups.isEmpty()) {
                        // ── Empty state ───────────────────────────────────────
                        item {
                            Column(
                                modifier              = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                horizontalAlignment   = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text       = "Your trips",
                                    fontFamily = InstrumentSerifFamily,
                                    fontSize   = 26.sp,
                                    color      = bio.ink,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text      = "No trips yet. Tap Record to start.",
                                    fontSize  = 14.sp,
                                    color     = bio.ink3,
                                )
                            }
                        }
                    } else {
                        // ── 4. Trip cards ─────────────────────────────────────
                        items(groups, key = { it.id }) { item ->
                            BiophilicTripCard(
                                bio             = bio,
                                item            = item,
                                onClick         = { onGroupClick(item.id) },
                                onRename        = { renameTarget = item },
                                onExport        = { viewModel.onExportGroup(item.id) },
                                onDelete        = { deleteTarget = item },
                                exportInProgress = exportState is ExportState.InProgress,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Editorial header for the trip list.
 *
 * A 160dp tall Box containing:
 * - Organic decorative blob drawn on Canvas (mossPale2 at 35% alpha)
 * - Row: left = date label (12sp ink3 uppercase) + "Your trips" (InstrumentSerif 38sp)
 *         right = search icon button (44dp circle, surface bg, line2 border)
 *
 * The date is formatted as "EEEE, MMMM d" (e.g. "Friday, April 25") using today's date.
 */
@Composable
private fun BiophilicHeader(bio: BiophilicColors) {
    val today = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    // Blob color constant — mossPale2 at 35% opacity for a subtle organic feel.
    val blobColor = bio.mossPale2.copy(alpha = 0.35f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(horizontal = 24.dp),
    ) {
        // Decorative organic blob drawn behind the text content.
        FoundationCanvas(modifier = Modifier.fillMaxSize()) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.55f, 0f)
                cubicTo(
                    size.width * 0.85f, size.height * 0.05f,
                    size.width * 0.95f, size.height * 0.40f,
                    size.width * 0.80f, size.height * 0.70f,
                )
                cubicTo(
                    size.width * 0.65f, size.height * 1.00f,
                    size.width * 0.40f, size.height * 0.85f,
                    size.width * 0.50f, size.height * 0.50f,
                )
                cubicTo(
                    size.width * 0.45f, size.height * 0.20f,
                    size.width * 0.38f, size.height * 0.02f,
                    size.width * 0.55f, 0f,
                )
                close()
            }
            drawPath(path, color = blobColor)
        }

        Row(
            modifier            = Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Day + date label — small, uppercase, subdued
                Text(
                    text          = today.uppercase(Locale.getDefault()),
                    fontSize      = 12.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight    = FontWeight.Medium,
                    color         = bio.ink3,
                )
                Spacer(Modifier.height(4.dp))
                // Main editorial heading in serif font
                Text(
                    text       = "Your trips",
                    fontFamily = InstrumentSerifFamily,
                    fontSize   = 38.sp,
                    color      = bio.ink,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(12.dp))

            // Search button — 44dp circle, surface background, line2 border.
            // Uses IconButton so it is tappable (touch target + accessibility semantics).
            IconButton(
                onClick  = { /* search — future */ },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(bio.surface)
                    .border(1.dp, bio.line2, CircleShape),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint               = bio.ink2,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stat pills
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Three equal-width stat pills showing trip aggregate counts.
 *
 * Stats are computed from the loaded groups list:
 * - TRIPS: number of TripGroups
 * - DISTANCE: sum of totalDistanceMeters / 1000, formatted to 1 decimal
 * - MOMENTS: sum of (photoCount + videoCount + noteCount) across all groups
 *
 * Each pill uses InstrumentSerif for the value (22sp) and a small label (10sp ink3).
 */
@Composable
private fun StatPillsRow(bio: BiophilicColors, groups: List<TripGroupItem>) {
    val tripCount   = remember(groups) { groups.size.toString() }
    val distanceKm  = remember(groups) { "${"%.1f".format(groups.sumOf { it.totalDistanceMeters } / 1000.0)} km" }
    val momentCount = remember(groups) { groups.sumOf { it.photoCount + it.videoCount + it.noteCount }.toString() }

    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatPill(bio = bio, value = tripCount,   label = "TRIPS",    modifier = Modifier.weight(1f))
        StatPill(bio = bio, value = distanceKm,  label = "DISTANCE", modifier = Modifier.weight(1f))
        StatPill(bio = bio, value = momentCount, label = "MOMENTS",  modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatPill(
    bio:      BiophilicColors,
    value:    String,
    label:    String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text       = value,
            fontFamily = InstrumentSerifFamily,
            fontSize   = 22.sp,
            color      = bio.ink,
        )
        Text(
            text          = label,
            fontSize      = 10.sp,
            letterSpacing = 0.4.sp,
            fontWeight    = FontWeight.Medium,
            color         = bio.ink3,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Filter row
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Visual-only filter row — no ViewModel backing.
 *
 * Shows a "Recent" label and two chips: "All" (index 0) and "This week" (index 1).
 * State is local to the composition; no filtering is actually applied to the data.
 * This is a placeholder for future filter functionality.
 */
@Composable
private fun FilterRow(bio: BiophilicColors) {
    var filterSelected by remember { mutableStateOf(0) }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text      = "Recent",
            fontSize  = 13.sp,
            color     = bio.ink2,
            fontWeight = FontWeight.Medium,
            modifier  = Modifier.weight(1f),
        )

        FilterChip(bio = bio, label = "All",       selected = filterSelected == 0) { filterSelected = 0 }
        FilterChip(bio = bio, label = "This week", selected = filterSelected == 1) { filterSelected = 1 }
    }
}

@Composable
private fun FilterChip(
    bio:      BiophilicColors,
    label:    String,
    selected: Boolean,
    onClick:  () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier         = Modifier
            .clip(shape)
            .then(
                if (selected)
                    Modifier.background(bio.mossPale2)
                else
                    Modifier.border(1.dp, bio.line, shape),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text     = label,
            fontSize = 12.sp,
            color    = if (selected) bio.mossDeep else bio.ink3,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Trip card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Biophilic trip card — horizontal layout with mini-map thumbnail and text content.
 *
 * Layout:
 * ```
 * ┌────────────────────────────────────────────┐
 * │ [BiophilicMiniMap 110×110] │ Name      [⋮] │
 * │                            │ meta tags row  │
 * └────────────────────────────────────────────┘
 * ```
 *
 * The three-dot menu (Rename / Export / Delete) is preserved from the previous design.
 * Location row is omitted — the data model has no freeform location string.
 *
 * Meta tags: distance | dateRange | N moments (moments in mossDeep, others in ink3)
 */
@Composable
private fun BiophilicTripCard(
    bio:             BiophilicColors,
    item:            TripGroupItem,
    onClick:         () -> Unit,
    onRename:        () -> Unit,
    onExport:        () -> Unit,
    onDelete:        () -> Unit,
    exportInProgress: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }

    val cardMomentCount = remember(item) { (item.photoCount + item.videoCount + item.noteCount).toInt() }
    val distanceText    = remember(item) { "${"%.1f".format(item.totalDistanceMeters / 1000.0)} km" }
    val momentsText     = remember(item) { "$cardMomentCount moments" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape  = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = bio.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, bio.line2),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // ── BiophilicMiniMap — clipped to top-start / bottom-start 26dp corners ──
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart    = 26.dp,
                            bottomStart = 26.dp,
                            topEnd      = 0.dp,
                            bottomEnd   = 0.dp,
                        ),
                    ),
            ) {
                BiophilicMiniMap(
                    bio         = bio,
                    trackPoints = item.thumbnailPoints,
                    momentCount = cardMomentCount,
                    modifier    = Modifier.fillMaxSize(),
                )
            }

            // ── Card text content ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp),
            ) {
                // Name row with three-dot menu
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = item.name,
                        fontFamily = InstrumentSerifFamily,
                        fontSize   = 20.sp,
                        color      = bio.ink,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f),
                    )

                    // Three-dot menu — Rename / Export / Delete (preserved from original)
                    Box {
                        IconButton(
                            onClick  = { showMenu = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
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
                            DropdownMenuItem(
                                text        = { Text(stringResource(R.string.action_export)) },
                                onClick     = { showMenu = false; onExport() },
                                enabled     = !exportInProgress,
                                leadingIcon = {
                                    // Show a small spinner while any export is in progress so the
                                    // user knows the action is running. Also disabled to prevent
                                    // concurrent exports.
                                    if (exportInProgress) {
                                        CircularProgressIndicator(
                                            modifier    = Modifier.size(24.dp),
                                            strokeWidth = 2.5.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.FileDownload, contentDescription = null)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text        = {
                                    Text(
                                        stringResource(R.string.action_delete),
                                        color = bio.recordRed,
                                    )
                                },
                                onClick     = { showMenu = false; onDelete() },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint               = bio.recordRed,
                                    )
                                },
                            )
                        }
                    }
                }

                // Meta tags row — distance | dateRange | N moments
                Row(
                    modifier              = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(text = distanceText,  fontSize = 11.sp, color = bio.ink3)
                    Text(text = item.dateRange, fontSize = 11.sp, color = bio.ink3)
                    Text(text = momentsText,    fontSize = 11.sp, color = bio.mossDeep)
                }
            }
        }
    }
}
