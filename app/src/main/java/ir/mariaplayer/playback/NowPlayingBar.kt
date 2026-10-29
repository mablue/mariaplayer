package ir.mariaplayer.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController

@Composable
fun NowPlayingBar(controller: MediaController?) {
    if (controller == null) return
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString() ?: "") }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                title = mediaMetadata.title?.toString() ?: ""
                artist = mediaMetadata.artist?.toString().orEmpty()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    if (title.isEmpty()) return

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                if (artist.isNotEmpty()) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                Text(if (playing) "Pause" else "Play")
            }
        }
    }
}
