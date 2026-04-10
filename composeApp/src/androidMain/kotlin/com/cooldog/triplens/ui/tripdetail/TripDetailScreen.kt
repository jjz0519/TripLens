package com.cooldog.triplens.ui.tripdetail

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.R
import com.cooldog.triplens.ui.common.RenameDialog
import com.cooldog.triplens.ui.common.formatDistance
import com.cooldog.triplens.ui.common.formatDuration
import com.cooldog.triplens.ui.common.modeEmoji

/**
 * TripGroup Detail screen — displays a header with aggregate stats and a list of sessions
 * with transport mode breakdowns.
 *
 * @param viewModel      The [TripDetailViewModel] providing state and handling actions.
 * @param onSessionClick Called when a session row is tapped — navigates to SessionReviewRoute.
 * @param onBack         Called when the back button is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    onSessionClick: (sessionId: String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state — which session is being renamed.
    var renameTarget by remember { mutableStateOf<SessionItem?>(null) }

    // Collect one-shot events for snackbar display.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TripDetailViewModel.Event.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────
    renameTarget?.let { item ->
        RenameDialog(
            currentName = item.name,
            title = stringResource(R.string.trip_detail_rename_session_title),
            onConfirm = { newName ->
                viewModel.onRenameSession(item.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // ── Screen layout ─────────────────────────────────────────────────────────
    val loadedState = uiState as? TripDetailViewModel.UiState.Loaded

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = loadedState?.groupName ?: stringResource(R.string.trip_detail_fallback_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Export button in the top bar (dev button — shows file path snackbar).
                    IconButton(onClick = { viewModel.onExportGroup() }) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = stringResource(R.string.action_export),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is TripDetailViewModel.UiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is TripDetailViewModel.UiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }

                is TripDetailViewModel.UiState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 8.dp,
                            bottom = 16.dp,
                        ),
                    ) {
                        // ── Header card with aggregate stats ──────────────────────
                        item {
                            GroupStatsHeader(
                                totalDistance = state.totalDistanceMeters,
                                totalDuration = state.totalDurationSeconds,
                                sessionCount = state.sessionCount,
                            )
                        }

                        // ── Session rows ──────────────────────────────────────────
                        items(state.sessions, key = { it.id }) { session ->
                            SessionRow(
                                item = session,
                                onClick = { onSessionClick(session.id) },
                                onRename = { renameTarget = session },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Header card showing aggregate stats for the trip group.
 */
@Composable
private fun GroupStatsHeader(
    totalDistance: Double,
    totalDuration: Long,
    sessionCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatColumn(label = stringResource(R.string.stat_distance), value = formatDistance(totalDistance))
            StatColumn(label = stringResource(R.string.stat_duration), value = formatDuration(totalDuration))
            StatColumn(
                label = stringResource(R.string.stat_sessions),
                value = sessionCount.toString(),
            )
        }
    }
}

/**
 * A single stat column in the header card.
 */
@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

/**
 * A single session row in the detail list.
 *
 * Layout:
 * ```
 * Session 1                            [⋮]
 * Apr 2, 10:30 – 12:45 · 2h 15m
 * 🚶 2.3 km  🚗 45.0 km
 * ```
 */
@Composable
private fun SessionRow(
    item: SessionItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Session name
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))

                // Date/time range and duration
                Text(
                    text = "${item.dateTimeRange} · ${formatDuration(item.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                // Transport breakdown — show mode emoji + distance for each mode.
                if (item.transportBreakdown.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item.transportBreakdown.forEach { stat ->
                            Text(
                                text = "${modeEmoji(stat.mode)} ${formatDistance(stat.distanceMeters)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Three-dot menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.trip_list_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

// formatDistance, formatDuration, modeEmoji are imported from ui/common/FormatUtils.kt
