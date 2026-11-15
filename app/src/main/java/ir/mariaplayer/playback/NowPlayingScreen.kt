package ir.mariaplayer.playback

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
fun NowPlayingScreen(
    controller: MediaController?,
    onClose: () -> Unit,
    onLyricsClick: () -> Unit,
) {
    if (controller == null) { onClose(); return }
    val repo = LocalPlaybackRepository.current ?: run { onClose(); return }
    val state by repo.state.collectAsState()
    val context = LocalContext.current

    val art by produceState<Bitmap?>(
        initialValue = state.trackId?.let { AlbumArtCache.peek(it) },
        key1 = state.trackId,
    ) {
        val id = state.trackId
        if (id != null && value == null) {
            val uri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
            if (uri != null) value = AlbumArtCache.get(context, id, uri)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_arrow_down), "Close")
                }
                Text(
                    "Now Playing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = art
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        state.title.take(1).uppercase().ifBlank { "\u266A" },
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                state.title.ifBlank { "\u2014" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.artist.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatNowTime(state.positionMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = if (state.durationMs > 0)
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    else 0f,
                    onValueChange = { v ->
                        if (state.durationMs > 0) {
                            controller.seekTo((v * state.durationMs).toLong())
                        }
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    formatNowTime(state.durationMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { controller.seekToPreviousMediaItem() },
                    enabled = state.hasPrev,
                ) {
                    Icon(painterResource(R.drawable.ic_skip_previous), "Previous",
                        modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = {
                    val p = (state.positionMs - 15_000L).coerceAtLeast(0L)
                    controller.seekTo(p)
                }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_replay), "Rewind 15s",
                            modifier = Modifier.size(32.dp))
                        Text("15", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { if (state.isPlaying) controller.pause() else controller.play() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        if (state.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(42.dp),
                    )
                }
                IconButton(onClick = {
                    val raw = state.positionMs + 30_000L
                    val p = if (state.durationMs > 0) raw.coerceAtMost(state.durationMs) else raw
                    controller.seekTo(p)
                }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_forward), "Forward 30s",
                            modifier = Modifier.size(32.dp))
                        Text("30", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(
                    onClick = { controller.seekToNextMediaItem() },
                    enabled = state.hasNext,
                ) {
                    Icon(painterResource(R.drawable.ic_skip_next), "Next",
                        modifier = Modifier.size(36.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(onClick = onLyricsClick) {
                Icon(painterResource(R.drawable.ic_lyrics), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Lyrics")
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

private fun formatNowTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
