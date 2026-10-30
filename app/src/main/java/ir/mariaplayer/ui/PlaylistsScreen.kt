package ir.mariaplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
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
import ir.mariaplayer.playback.NowPlayingBar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    controller: MediaController?,
    onLyricsClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playlists by produceState<List<Playlist>>(initialValue = emptyList()) {
        PlaylistPrefs.observe(context).collectLatest { value = it }
    }

    var createDialogOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar(controller, onLyricsClick) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                newName = ""
                createDialogOpen = true
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "New playlist",
                )
            }
        },
    ) { inner ->
        if (playlists.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No playlists yet.\nTap + to create one.",
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(inner)) {
                items(playlists, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.trackIds.size} tracks") },
                        trailingContent = {
                            IconButton(onClick = { confirmDeleteId = p.id }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = "Delete playlist",
                                )
                            }
                        },
                        modifier = Modifier.clickable { onOpenPlaylist(p.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (createDialogOpen) {
        AlertDialog(
            onDismissRequest = { createDialogOpen = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        scope.launch { PlaylistPrefs.create(context, newName) }
                        createDialogOpen = false
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { createDialogOpen = false }) { Text("Cancel") }
            },
        )
    }

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete playlist?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { PlaylistPrefs.delete(context, id) }
                    confirmDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}
