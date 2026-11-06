package ir.mariaplayer.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import java.util.Locale

@Composable
fun NowPlayingBar(controller: MediaController?, onLyricsClick: () -> Unit = {}) {
    if (controller == null) return
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString() ?: "") }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }
    var hasPrev by remember { mutableStateOf(controller.hasPreviousMediaItem()) }
    var hasNext by remember { mutableStateOf(controller.hasNextMediaItem()) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                title = mediaMetadata.title?.toString() ?: ""
                artist = mediaMetadata.artist?.toString().orEmpty()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                hasPrev = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
                positionMs = 0L
                dragging = false
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                hasPrev = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                hasPrev = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller) {
        while (true) {
            if (!dragging) {
                positionMs = controller.currentPosition.coerceAtLeast(0L)
                durationMs = controller.duration.coerceAtLeast(0L)
            }
            kotlinx.coroutines.delay(500)
        }
    }

    if (title.isEmpty()) return

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    if (artist.isNotEmpty()) {
                        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onLyricsClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics),
                        contentDescription = "Lyrics",
                    )
                }
            }

            if (durationMs > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(if (dragging) dragValue.toLong() else positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = if (dragging) dragValue else positionMs.toFloat(),
                        onValueChange = { v -> dragging = true; dragValue = v },
                        onValueChangeFinished = {
                            controller.seekTo(dragValue.toLong())
                            positionMs = dragValue.toLong()
                            dragging = false
                        },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = { controller.seekToPreviousMediaItem() },
                    enabled = hasPrev,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous),
                        contentDescription = "Previous track",
                    )
                }
                IconButton(
                    onClick = {
                        val newPos = (controller.currentPosition - 15_000L).coerceAtLeast(0L)
                        controller.seekTo(newPos)
                        positionMs = newPos
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_replay),
                            contentDescription = "Rewind 15 seconds",
                            modifier = Modifier.size(28.dp),
                        )
                        Text("15", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                IconButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        ),
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val raw = controller.currentPosition + 30_000L
                        val newPos = if (durationMs > 0) raw.coerceAtMost(durationMs) else raw
                        controller.seekTo(newPos)
                        positionMs = newPos
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_forward),
                            contentDescription = "Forward 30 seconds",
                            modifier = Modifier.size(28.dp),
                        )
                        Text("30", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                IconButton(
                    onClick = { controller.seekToNextMediaItem() },
                    enabled = hasNext,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next),
                        contentDescription = "Next track",
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
