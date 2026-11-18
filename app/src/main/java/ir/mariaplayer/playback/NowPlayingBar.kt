package ir.mariaplayer.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import ir.mariaplayer.art.AlbumArtCache
import java.util.Locale

@Composable
fun NowPlayingBar(
    controller: MediaController?,
    onLyricsClick: () -> Unit = {},
    onExpand: (() -> Unit)? = null,
) {
    if (controller == null) return
    val repository = LocalPlaybackRepository.current ?: return
    val state by repository.state.collectAsState()
    if (state.title.isEmpty()) return

    val art by produceState<android.graphics.Bitmap?>(
        initialValue = state.trackId?.let { AlbumArtCache.peek(it) },
        key1 = state.trackId,
    ) {
        val id = state.trackId
        if (id != null && value == null) value = AlbumArtCache.peek(id)
    }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val shownPositionMs: Long =
        if (dragging && state.durationMs > 0)
            (dragFraction * state.durationMs).toLong()
        else state.positionMs

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = onExpand != null) { onExpand?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = art
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_playlist),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .clickable(enabled = onExpand != null) { onExpand?.invoke() },
                ) {
                    Text(
                        state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.artist.isNotEmpty()) {
                        Text(
                            state.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = onLyricsClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics),
                        contentDescription = "Lyrics",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = { controller.seekToPreviousMediaItem() },
                    enabled = state.hasPrev,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous),
                        contentDescription = "Previous track",
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = { if (state.isPlaying) controller.pause() else controller.play() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state.isPlaying) R.drawable.ic_pause
                            else R.drawable.ic_play_arrow
                        ),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp),
                    )
                }

                IconButton(
                    onClick = { controller.seekToNextMediaItem() },
                    enabled = state.hasNext,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next),
                        contentDescription = "Next track",
                        modifier = Modifier.size(22.dp),
                    )
                }

                if (onExpand != null) {
                    IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_up),
                            contentDescription = "Open player",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTime(shownPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SeekPill(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    dragging = dragging,
                    dragFraction = dragFraction,
                    onDragStart = { f -> dragging = true; dragFraction = f },
                    onDrag = { f -> dragFraction = f },
                    onDragEnd = {
                        if (state.durationMs > 0) {
                            controller.seekTo((dragFraction * state.durationMs).toLong())
                        }
                        dragging = false
                    },
                    onTap = { ms -> if (state.durationMs > 0) controller.seekTo(ms) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                Text(
                    formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeekPill(
    positionMs: Long,
    durationMs: Long,
    dragging: Boolean,
    dragFraction: Float,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)

    Box(
        modifier
            .height(28.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onTap((f * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (durationMs > 0) {
                            onDragStart((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (durationMs > 0) {
                            onDrag((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackH = 3.dp.toPx()
            val thumbR = if (dragging) 9.dp.toPx() else 6.dp.toPx()
            val cy = size.height / 2f
            val fraction = when {
                durationMs <= 0 -> 0f
                dragging -> dragFraction.coerceIn(0f, 1f)
                else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            }
            val activeEnd = size.width * fraction
            val thumbX = activeEnd.coerceIn(thumbR, size.width - thumbR)

            drawRoundRect(
                color = inactive,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
            )
            if (activeEnd > 0f) {
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(activeEnd.coerceAtLeast(trackH / 2f), trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
                )
            }
            drawCircle(color = primary, radius = thumbR, center = Offset(thumbX, cy))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
