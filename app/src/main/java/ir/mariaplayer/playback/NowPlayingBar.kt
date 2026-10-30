package ir.mariaplayer.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import ir.mariaplayer.R

@Composable
fun NowPlayingBar(controller: MediaController?) {
    if (controller == null) return
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString() ?: "") }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }
    var hasPrev by remember { mutableStateOf(controller.hasPreviousMediaItem()) }
    var hasNext by remember { mutableStateOf(controller.hasNextMediaItem()) }

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
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                hasPrev = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    if (title.isEmpty()) return

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
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

            IconButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                Icon(
                    painter = painterResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    ),
                    contentDescription = if (playing) "Pause" else "Play",
                )
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

            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                if (artist.isNotEmpty()) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
