package com.cooldog.triplens.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import com.cooldog.triplens.ui.recording.MediaItem

/**
 * Photo or video thumbnail card — fixed 64 dp height, width from the image's aspect ratio.
 *
 * [ContentScale.FillHeight] scales the image so its height fills 64 dp;
 * width follows the resulting image width (minimum 64 dp to prevent a zero-width flash).
 *
 * For videos, [VideoFrameDecoder] is added to the [ImageRequest] so Coil decodes the first
 * frame from the video container (works with content:// URIs from MediaStore). Without this,
 * Coil falls back to the generic image pipeline which cannot decode video containers.
 */
@Composable
internal fun PhotoCard(contentUri: String, isVideo: Boolean) {
    val context = LocalContext.current
    val model = if (isVideo) {
        // VideoFrameDecoder extracts a bitmap from the first frame of the video.
        ImageRequest.Builder(context)
            .data(contentUri)
            .decoderFactory(VideoFrameDecoder.Factory())
            .build()
    } else {
        contentUri
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(64.dp),
    ) {
        Box {
            AsyncImage(
                model = model,
                contentDescription = if (isVideo) "Video thumbnail" else "Photo",
                contentScale = ContentScale.FillHeight,
                modifier = Modifier.height(64.dp),
            )
            if (isVideo) {
                // Dark scrim + play icon so the overlay reads on any thumbnail brightness.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                )
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * Text note card — 64×100 dp.
 *
 * No icon; the full preview text fills the card with wrapping.
 * [TextOverflow.Ellipsis] truncates if the content is longer than the card can show.
 * Tinted with [secondaryContainer] to distinguish from photo/video rows at a glance.
 */
@Composable
internal fun TextNoteCard(item: MediaItem.TextNote) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.size(width = 100.dp, height = 64.dp),
    ) {
        Text(
            text = item.preview,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * Voice note card — 64×100 dp.
 *
 * Shows a mic icon and M:SS duration. No animation — static display only.
 * Tinted with [tertiaryContainer] for clear visual differentiation.
 */
@Composable
internal fun VoiceNoteCard(item: MediaItem.VoiceNote) {
    val m = item.durationSeconds / 60
    val s = item.durationSeconds % 60

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.size(width = 100.dp, height = 64.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "%d:%02d".format(m, s),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
