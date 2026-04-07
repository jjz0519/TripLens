package com.cooldog.triplens.ui.triplist

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.ui.common.DeleteConfirmDialog
import com.cooldog.triplens.ui.common.RenameDialog

/**
 * Trip List screen — displays all TripGroup cards in a scrollable list.
 *
 * Each card shows the group name, date range, aggregate stats, trajectory thumbnail,
 * and a three-dot menu with Rename / Export / Delete actions.
 *
 * @param viewModel  The [TripListViewModel] providing state and handling actions.
 * @param onGroupClick Called when a card is tapped — navigates to TripDetailRoute(groupId).
 */
@Composable
fun TripListScreen(
    viewModel: TripListViewModel,
    onGroupClick: (groupId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state — which group is being renamed or deleted.
    var renameTarget by remember { mutableStateOf<TripGroupItem?>(null) }
    var deleteTarget by remember { mutableStateOf<TripGroupItem?>(null) }

    // Collect one-shot events for snackbar display.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TripListViewModel.Event.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    renameTarget?.let { item ->
        RenameDialog(
            currentName = item.name,
            title = "Rename Trip",
            onConfirm = { newName ->
                viewModel.onRenameGroup(item.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { item ->
        DeleteConfirmDialog(
            itemName = item.name,
            onConfirm = {
                viewModel.onDeleteGroup(item.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // ── Main content ──────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is TripListViewModel.UiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            is TripListViewModel.UiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }

            is TripListViewModel.UiState.Loaded -> {
                if (state.groups.isEmpty()) {
                    // Empty state — clean minimal message.
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No trips yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Start recording to create your first trip",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // Add top/bottom padding so cards don't touch screen edges.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 16.dp,
                            bottom = 16.dp,
                        ),
                    ) {
                        items(state.groups, key = { it.id }) { item ->
                            TripGroupCard(
                                item = item,
                                onClick = { onGroupClick(item.id) },
                                onRename = { renameTarget = item },
                                onExport = { viewModel.onExportGroup(item.id) },
                                onDelete = { deleteTarget = item },
                            )
                        }
                    }
                }
            }
        }

        // Snackbar anchored to the bottom of the screen.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * A single TripGroup card in the Trip List.
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────────────┐
 * │  [Thumbnail]  │  Group Name        [⋮]  │
 * │               │  Apr 2 – Apr 5          │
 * │               │  12.3 km · 3 sessions   │
 * │               │  📷 5  📹 2  📝 3        │
 * └──────────────────────────────────────────┘
 * ```
 */
@Composable
private fun TripGroupCard(
    item: TripGroupItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // ── Trajectory thumbnail ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                // Subtle background for the thumbnail area.
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    TrajectoryThumbnail(
                        points = item.thumbnailPoints,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // ── Card text content ─────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
            ) {
                // Group name — bold, single line with ellipsis.
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))

                // Date range
                Text(
                    text = item.dateRange,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                // Distance and session count
                Text(
                    text = formatDistance(item.totalDistanceMeters) +
                            " · ${item.sessionCount} session${if (item.sessionCount != 1L) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                // Media counts — only show non-zero counts.
                val mediaParts = buildList {
                    if (item.photoCount > 0) add("📷 ${item.photoCount}")
                    if (item.videoCount > 0) add("📹 ${item.videoCount}")
                    if (item.noteCount > 0) add("📝 ${item.noteCount}")
                }
                if (mediaParts.isNotEmpty()) {
                    Text(
                        text = mediaParts.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Three-dot menu ────────────────────────────────────────────────
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        onClick = {
                            showMenu = false
                            onExport()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Formats a distance in metres for display.
 * - Under 1000m: "123 m"
 * - 1000m+: "1.2 km"
 */
private fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()} m"
    } else {
        "${"%.1f".format(meters / 1000)} km"
    }
}
