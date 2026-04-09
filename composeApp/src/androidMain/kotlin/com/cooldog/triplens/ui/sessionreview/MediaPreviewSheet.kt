package com.cooldog.triplens.ui.sessionreview

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import com.cooldog.triplens.ui.recording.MediaItem
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectTransformGestures

/**
 * Bottom sheet that shows a full-size preview of a [TimelineItem.MediaEntry].
 *
 * ## Photo preview
 * Full-width image with [ContentScale.Fit]. Pinch-to-zoom is implemented via Compose's
 * [detectTransformGestures] + [graphicsLayer] — no additional library required (~20 lines).
 * Scale is clamped to [1f, 5f]; offset is reset to zero on dismiss.
 *
 * ## Voice note playback
 * [MediaPlayer] is created and prepared in [DisposableEffect], released on disposal.
 * Play state is polled every 200 ms via [LaunchedEffect] to update the seek slider.
 * The seek slider allows scrubbing to any position. This is minimal but functional (Q2).
 *
 * ## Text note
 * Scrollable text in a column. No interaction beyond reading.
 *
 * ## ModalBottomSheet
 * Follows the same pattern as [com.cooldog.triplens.ui.recording.RecordingScreen]:
 * `skipPartiallyExpanded = true` and `navigationBarsPadding()` on the content column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreviewSheet(
    entry: TimelineItem.MediaEntry,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            when (val item = entry.item) {
                is MediaItem.Photo -> PhotoPreview(contentUri = item.contentUri, isVideo = false)
                is MediaItem.Video -> PhotoPreview(contentUri = item.contentUri, isVideo = true)
                is MediaItem.VoiceNote -> VoiceNotePreview(
                    item = item,
                    audioFilePath = entry.audioFilePath,
                )
                is MediaItem.TextNote -> TextNotePreview(
                    // fullTextContent is the untruncated note body; fall back to preview if null.
                    content = entry.fullTextContent ?: item.preview,
                )
            }
        }
    }
}

/**
 * Full-screen photo or video poster frame with pinch-to-zoom.
 *
 * For videos, [VideoFrameDecoder] is added to the [ImageRequest] so Coil decodes the first
 * frame from the video container — identical to [com.cooldog.triplens.ui.common.PhotoCard].
 *
 * ## Pinch-to-zoom implementation
 * [detectTransformGestures] delivers zoom and pan deltas on each gesture frame.
 * [graphicsLayer] applies scale and translation without re-layout (GPU transform only).
 * Scale is clamped to [1f, 5f]; offset resets to zero when scale returns to 1.
 */
@Composable
private fun PhotoPreview(contentUri: String, isVideo: Boolean) {
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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = model,
        contentDescription = if (isVideo) "Video poster frame" else "Photo preview",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    // Only allow panning when zoomed in; at scale=1 offset stays zero.
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    )
}

/**
 * Voice note player with play/pause and a seek slider.
 *
 * ## MediaPlayer lifecycle
 * Created once in [DisposableEffect]. Prepared synchronously (file is on local storage,
 * so prepare() is fast and safe on the main thread for files < ~10MB). Released in
 * [onDispose] to free the decoder and audio session.
 *
 * ## Seek slider
 * Uses a [Slider] driven by `currentMs / totalMs` ratio. During `onValueChange` the
 * slider position is updated immediately for responsive feel; `seekTo` is called on
 * `onValueChangeFinished` to avoid continuous seeks while the user drags.
 *
 * ## Progress polling
 * While playing, [LaunchedEffect] polls `mediaPlayer.currentPosition` every 200 ms.
 * Polling stops when `isPlaying` becomes false (pause or completion).
 */
@Composable
private fun VoiceNotePreview(item: MediaItem.VoiceNote, audioFilePath: String?) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentMs by remember { mutableFloatStateOf(0f) }
    // durationSeconds from MediaItem is the recorded value; MediaPlayer.duration is the
    // ground truth after prepare() but may differ slightly. Use MediaPlayer.duration once
    // available, fall back to the model value while preparing.
    var totalMs by remember { mutableFloatStateOf((item.durationSeconds * 1000).toFloat()) }
    var isPrepared by remember { mutableStateOf(false) }

    val mediaPlayer = remember { MediaPlayer() }

    // Prepare MediaPlayer from the local file path. Release on disposal.
    // Uses prepareAsync() to avoid blocking the main thread while the file is buffered.
    // isPrepared gates all playback controls so the UI is safe before onPrepared fires.
    DisposableEffect(audioFilePath) {
        if (audioFilePath != null) {
            try {
                mediaPlayer.setDataSource(audioFilePath)
                mediaPlayer.setOnPreparedListener { mp ->
                    totalMs = mp.duration.toFloat().coerceAtLeast(1f)
                    isPrepared = true
                    Log.d(TAG, "MediaPlayer prepared: path=$audioFilePath duration=${mp.duration}ms")
                }
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false
                    currentMs = 0f
                    mediaPlayer.seekTo(0)
                }
                mediaPlayer.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare MediaPlayer for $audioFilePath", e)
            }
        }
        onDispose {
            mediaPlayer.release()
            Log.d(TAG, "MediaPlayer released")
        }
    }

    // Poll current position every 200 ms while playing.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentMs = mediaPlayer.currentPosition.toFloat()
            delay(200)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Voice Note",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(8.dp))

        // Seek slider — drag to scrub, release to seek.
        var sliderValue by remember { mutableFloatStateOf(0f) }
        // Keep slider in sync with playback progress.
        sliderValue = if (totalMs > 0f) currentMs / totalMs else 0f

        Slider(
            value = sliderValue,
            onValueChange = { frac ->
                sliderValue = frac
                currentMs = frac * totalMs
            },
            onValueChangeFinished = {
                if (isPrepared) {
                    mediaPlayer.seekTo(currentMs.toInt())
                }
            },
            enabled = isPrepared,
            modifier = Modifier.fillMaxWidth(),
        )

        // Time labels: current / total
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(currentMs.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMs(totalMs.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Play / Pause button
        IconButton(
            onClick = {
                if (!isPrepared) return@IconButton
                if (isPlaying) {
                    mediaPlayer.pause()
                    isPlaying = false
                } else {
                    mediaPlayer.start()
                    isPlaying = true
                }
            },
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/**
 * Scrollable text display for a text note.
 *
 * @param content Full note content from [TimelineItem.MediaEntry.fullTextContent].
 */
@Composable
private fun TextNotePreview(content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Note",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Formats milliseconds as M:SS for the voice note time labels. */
private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

private const val TAG = "TripLens/MediaPreviewSheet"
