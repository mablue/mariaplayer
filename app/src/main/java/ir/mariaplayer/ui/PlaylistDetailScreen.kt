package ir.mariaplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import ir.mariaplayer.data.Playlist
import ir.mariaplayer.data.PlaylistPrefs
import ir.mariaplayer.data.Track
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

            else -> LazyColumn(Modifier.fillMaxSize().padding(inner)) {
                items(tracks, key = { it.id }) { t ->
                    val idx = tracks.indexOfFirst { it.id == t.id }
                    val canUp = editMode && idx > 0
                    val canDown = editMode && idx >= 0 && idx < tracks.lastIndex

                    ListItem(
                        headlineContent = { Text(t.title) },
                        supportingContent = {
                            Text(
                                listOfNotNull(t.artist, formatDurationLocal(t.durationMs))
                                    .joinToString(" - ")
                            )
                        },
                        trailingContent = {
                            if (editMode) {
                                Column {
                                    IconButton(
                                        enabled = canUp,
                                        onClick = {
                                            if (canUp) {
                                                scope.launch {
                                                    PlaylistPrefs.reorder(
                                                        context, playlistId, idx - 1, t.id,
                                                    )
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_up),
                                            contentDescription = "Move up",
                                            tint = if (canUp) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        enabled = canDown,
                                        onClick = {
                                            if (canDown) {
                                                scope.launch {
                                                    PlaylistPrefs.reorder(
                                                        context, playlistId, idx + 1, t.id,
                                                    )
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_down),
                                            contentDescription = "Move down",
                                            tint = if (canDown) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                IconButton(onClick = {
                                    scope.launch {
                                        PlaylistPrefs.removeTrack(context, playlistId, t.id)
                                    }
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "Remove from playlist",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = !editMode) {
                            if (idx >= 0) onPlayTrack(tracks, idx)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatDurationLocal(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
