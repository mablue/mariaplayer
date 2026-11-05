package ir.mariaplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import ir.mariaplayer.data.Playlist
import ir.mariaplayer.data.PlaylistPrefs
import ir.mariaplayer.data.Track
import ir.mariaplayer.playback.NowPlayingBar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    allTracks: List<Track>,
    controller: MediaController?,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onBack: () -> Unit,
    onLyricsClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playlists by produceState<List<Playlist>>(initialValue = emptyList()) {
        PlaylistPrefs.observe(context).collectLatest { value = it }
    }
    val playlist = playlists.firstOrNull { it.id == playlistId }

    val tracks = remember(playlist, allTracks) {
        val byId = allTracks.associateBy { it.id }
        playlist?.trackIds?.mapNotNull { byId[it] } ?: emptyList()
    }
    var editMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (tracks.size > 1) {
                        TextButton(onClick = { editMode = !editMode }) {
                            Text(if (editMode) "Done" else "Edit")
                        }
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar(controller, onLyricsClick) },
    ) { inner ->
        when {
            playlist == null -> Box(
                Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) { Text("Playlist not found") }

            tracks.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "This playlist is empty.\nLong-press a track in your library to add one.",
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tracks, key = { it.id }) { t ->
                    val idx = tracks.indexOfFirst { it.id == t.id }
                    val rawIdx = playlist.trackIds.indexOf(t.id)
                    val canUp = editMode && rawIdx > 0
                    val canDown = editMode && rawIdx >= 0 && rawIdx < playlist.trackIds.lastIndex

                    GlassCard(
                        onClick = if (!editMode && idx >= 0) {
                            { onPlayTrack(tracks, idx) }
                        } else null,
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(40.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${rawIdx + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        t.artist,
                                        formatDurationLocal(t.durationMs),
                                    ).joinToString(" · "),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (editMode) {
                                Column {
                                    IconButton(
                                        enabled = canUp,
                                        onClick = {
                                            if (canUp) {
                                                scope.launch {
                                                    val r = playlist.trackIds.indexOf(t.id)
                                                    if (r > 0) PlaylistPrefs.reorder(
                                                        context, playlistId, r - 1, t.id,
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_up),
                                            contentDescription = "Move up",
                                            tint = if (canUp) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        enabled = canDown,
                                        onClick = {
                                            if (canDown) {
                                                scope.launch {
                                                    val r = playlist.trackIds.indexOf(t.id)
                                                    if (r >= 0 && r < playlist.trackIds.lastIndex)
                                                        PlaylistPrefs.reorder(
                                                            context, playlistId, r + 1, t.id,
                                                        )
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_down),
                                            contentDescription = "Move down",
                                            tint = if (canDown) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            PlaylistPrefs.removeTrack(
                                                context, playlistId, t.id,
                                            )
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "Remove from playlist",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDurationLocal(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
