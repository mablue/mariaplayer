package ir.mariaplayer.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import java.util.Locale

@Composable
fun NowPlayingBar(controller: MediaController?, onLyricsClick: () -> Unit = {}) {
    if (controller == null) return
    val repository = LocalPlaybackRepository.current ?: return
    val state by repository.state.collectAsState()
    if (state.title.isEmpty()) return

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    if (state.artist.isNotEmpty()) {
                        Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

            if (state.durationMs > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(if (dragging) dragValue.toLong() else state.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = if (dragging) dragValue else state.positionMs.toFloat(),
                        onValueChange = { v -> dragging = true; dragValue = v },
                        onValueChangeFinished = {
                            controller.seekTo(dragValue.toLong())
                            dragging = false
                        },
                        valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(formatTime(state.durationMs),
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
                    enabled = state.hasPrev,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous),
                        contentDescription = "Previous track",
                    )
                }
                IconButton(
                    onClick = {
                        val newPos = (state.positionMs - 15_000L).coerceAtLeast(0L)
                        controller.seekTo(newPos)
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
                IconButton(onClick = {
                    if (state.isPlaying) controller.pause() else controller.play()
                }) {
                    Icon(
                        painter = painterResource(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        ),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val raw = state.positionMs + 30_000L
                        val newPos = if (state.durationMs > 0) raw.coerceAtMost(state.durationMs) else raw
                        controller.seekTo(newPos)
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
                    enabled = state.hasNext,
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
