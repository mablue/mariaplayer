package ir.mariaplayer.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import ir.mariaplayer.art.AlbumArtCache

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
        if (id != null && value == null) {
            value = AlbumArtCache.peek(id)
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(enabled = onExpand != null) { onExpand?.invoke() },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
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
                        .padding(horizontal = 12.dp),
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

                IconButton(onClick = onLyricsClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics),
                        contentDescription = "Lyrics",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = {
                        if (state.isPlaying) controller.pause() else controller.play()
                    },
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

                if (onExpand != null) {
                    IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_up),
                            contentDescription = "Open player",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            val progress: Float = if (state.durationMs > 0L) {
                (state.positionMs.toFloat() / state.durationMs.toFloat())
                    .coerceIn(0f, 1f)
            } else 0f

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
