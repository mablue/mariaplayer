package ir.mariaplayer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.mariaplayer.data.Playlist
import ir.mariaplayer.data.PlaylistPrefs
import ir.mariaplayer.data.Track
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    track: Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val playlists by produceState<List<Playlist>>(initialValue = emptyList()) {
        PlaylistPrefs.observe(context).collectLatest { value = it }
    }

    var newNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                track.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(playlists, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.trackIds.size} tracks") },
                        modifier = Modifier.clickable {
                            scope.launch {
                                val added = PlaylistPrefs.addTrack(context, p.id, track.id)
                                Toast.makeText(
                                    context,
                                    if (added) "Added to ${p.name}" else "Already in ${p.name}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            onDismiss()
                        },
                    )
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                "+ New playlist",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable {
                            newName = ""
                            newNameDialog = true
                        },
                    )
                }
                item { Column(Modifier.padding(top = 16.dp)) {} }
            }
        }
    }

    if (newNameDialog) {
        AlertDialog(
            onDismissRequest = { newNameDialog = false },
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
                        val name = newName.trim()
                        scope.launch {
                            val id = PlaylistPrefs.create(context, name)
                            PlaylistPrefs.addTrack(context, id, track.id)
                            Toast.makeText(
                                context, "Created $name and added track",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        newNameDialog = false
                        onDismiss()
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newNameDialog = false }) { Text("Cancel") }
            },
        )
    }
}
