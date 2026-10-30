package ir.mariaplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import ir.mariaplayer.data.FavoritesPrefs
import ir.mariaplayer.data.LibraryPrefs
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.SortMode
import ir.mariaplayer.data.Track
import ir.mariaplayer.playback.NowPlayingBar
import ir.mariaplayer.playback.rememberPlayerController
import ir.mariaplayer.ui.MariaPlayerTheme
import ir.mariaplayer.ui.PermissionScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MariaPlayerTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    var hasAudio by remember { mutableStateOf(LibraryScanner.hasAudioPermission(context)) }
    if (!hasAudio) {
        PermissionScreen(onGranted = { hasAudio = true })
        return
    }

    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingDeleteUri
        val title = pendingDeleteTitle
        pendingDeleteUri = null
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            Toast.makeText(context, "Deleted: $title", Toast.LENGTH_SHORT).show()
            context.sendBroadcast(
                Intent(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
                    .setPackage(context.packageName)
                    .putExtra(ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI, uri.toString())
            )
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED) {
                    reloadKey++
                }
            }
        }
        val filter = IntentFilter(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    val tracks by produceState<List<Track>?>(initialValue = null, hasAudio, reloadKey) {
        value = LibraryScanner.scan(context)
    }
    val controller = rememberPlayerController()
    var shuffleOn by remember { mutableStateOf(controller?.shuffleModeEnabled ?: false) }

    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    var prefsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sortMode = LibraryPrefs.readSort(context)
        prefsLoaded = true
    }
    LaunchedEffect(sortMode, prefsLoaded) {
        if (prefsLoaded) LibraryPrefs.saveSort(context, sortMode)
    }

    val favorites by produceState<Set<Long>>(initialValue = emptySet()) {
        FavoritesPrefs.observe(context).collectLatest { value = it }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf("") }
    var favOnly by rememberSaveable { mutableStateOf(false) }

    val visible: List<Track>? = remember(tracks, query, sortMode, folderFilter, favOnly, favorites) {
        val base = tracks ?: return@remember null
        val q = query.trim().lowercase()
        base.asSequence()
            .filter { t ->
                (folderFilter.isEmpty() || t.folder == folderFilter) &&
                (!favOnly || t.id in favorites) &&
                (q.isEmpty() ||
                    t.title.lowercase().contains(q) ||
                    t.artist?.lowercase()?.contains(q) == true ||
                    t.album?.lowercase()?.contains(q) == true)
            }
            .sortedWith(comparatorFor(sortMode))
            .toList()
    }

    LaunchedEffect(controller, tracks) {
        val c = controller ?: return@LaunchedEffect
        val list = tracks ?: return@LaunchedEffect
        if (c.mediaItemCount > 0) return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val saved = PlaybackPrefs.read(context)
        val idx = saved.trackId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx < 0) return@LaunchedEffect
        playQueue(c, list, idx, saved.positionMs, autoPlay = false)
        shuffleOn = c.shuffleModeEnabled
    }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            val mi = c.currentMediaItem ?: continue
            val tid = mi.mediaId.toLongOrNull() ?: continue
            PlaybackPrefs.save(context, tid, c.currentPosition)
        }
    }

    LibraryScreen(
        allTracks = tracks,
        visible = visible,
        scanning = tracks == null,
        controller = controller,
        shuffleOn = shuffleOn,
        sortMode = sortMode,
        onSortChange = { sortMode = it },
        query = query,
        onQueryChange = { query = it },
        folderFilter = folderFilter,
        onFolderFilterChange = { folderFilter = it },
        favOnly = favOnly,
        onFavOnlyChange = { favOnly = it },
        favorites = favorites,
        onToggleFavorite = { id -> scope.launch { FavoritesPrefs.toggle(context, id) } },
        onShuffleClick = {
            val c = controller ?: return@LibraryScreen
            val list = visible ?: return@LibraryScreen
            if (c.mediaItemCount == 0 && list.isNotEmpty()) {
                playQueue(c, list, 0, 0L, autoPlay = true)
                c.shuffleModeEnabled = true
            } else {
                c.shuffleModeEnabled = !c.shuffleModeEnabled
            }
            shuffleOn = c.shuffleModeEnabled
        },
        onTrackClick = { t ->
            val c = controller ?: return@LibraryScreen
            val list = visible ?: return@LibraryScreen
            val idx = list.indexOfFirst { it.id == t.id }
            if (idx >= 0) playQueue(c, list, idx, 0L, autoPlay = true)
        },
        onDeleteRequest = { t ->
            val uri = Uri.parse(t.contentUri)
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                    pendingDeleteUri = uri
                    pendingDeleteTitle = t.title
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } catch (_: Exception) {
                    try {
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) {
                            Toast.makeText(context, "Deleted: " + t.title, Toast.LENGTH_SHORT).show()
                            context.sendBroadcast(
                                Intent(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
                                    .setPackage(context.packageName)
                                    .putExtra(ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI, t.contentUri)
                            )
                        }
                    } catch (e2: Exception) {
                        Toast.makeText(context, "Delete failed: " + e2.message, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        Toast.makeText(context, "Deleted: " + t.title, Toast.LENGTH_SHORT).show()
                        context.sendBroadcast(
                            Intent(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
                                .setPackage(context.packageName)
                                .putExtra(ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI, t.contentUri)
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Delete failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        },
    )
}

private fun playQueue(
    c: MediaController?,
    tracks: List<Track>,
    startIndex: Int,
    startPositionMs: Long,
    autoPlay: Boolean,
) {
    if (c == null || tracks.isEmpty()) return
    if (startIndex !in tracks.indices) return
    val items = tracks.map { t ->
        MediaItem.Builder()
            .setMediaId(t.id.toString())
            .setUri(t.contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()
            )
            .build()
    }
    c.setMediaItems(items, startIndex, startPositionMs)
    c.repeatMode = Player.REPEAT_MODE_ALL
    c.prepare()
    if (autoPlay) c.play()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    allTracks: List<Track>?,
    visible: List<Track>?,
    scanning: Boolean,
    controller: MediaController?,
    shuffleOn: Boolean,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    folderFilter: String,
    onFolderFilterChange: (String) -> Unit,
    favOnly: Boolean,
    onFavOnlyChange: (Boolean) -> Unit,
    favorites: Set<Long>,
    onToggleFavorite: (Long) -> Unit,
    onShuffleClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onDeleteRequest: (Track) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }

    val folderList = remember(allTracks) {
        allTracks?.map { it.folder }?.filter { it.isNotEmpty() }?.distinct()?.sorted()
            ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MariaPlayer") },
                actions = {
                    // Sort
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sort),
                                contentDescription = "Sort by ${sortMode.label}",
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = { onSortChange(m); sortMenuOpen = false },
                                )
                            }
                        }
                    }
                    // Shuffle
                    IconButton(onClick = onShuffleClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shuffle),
                            contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off",
                            tint = if (shuffleOn) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar(controller) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Search title, artist, album") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                // Favorites filter
                IconButton(onClick = { onFavOnlyChange(!favOnly) }) {
                    Icon(
                        painter = painterResource(
                            if (favOnly) R.drawable.ic_favorite_filled
                            else R.drawable.ic_favorite_border
                        ),
                        contentDescription =
                            if (favOnly) "Favorites filter on" else "Favorites filter off",
                        tint = if (favOnly) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Folder filter
                Box {
                    IconButton(onClick = { folderMenuOpen = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription =
                                if (folderFilter.isEmpty()) "Folder filter: all"
                                else "Folder filter: $folderFilter",
                            tint = if (folderFilter.isNotEmpty())
                                       MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = folderMenuOpen,
                        onDismissRequest = { folderMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("All folders") },
                            onClick = { onFolderFilterChange(""); folderMenuOpen = false },
                        )
                        folderList.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f) },
                                onClick = { onFolderFilterChange(f); folderMenuOpen = false },
                            )
                        }
                    }
                }
                if (folderFilter.isNotEmpty()) {
                    IconButton(onClick = { onFolderFilterChange("") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Clear folder filter",
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    scanning -> CenterText("Scanning library...")
                    visible.isNullOrEmpty() -> {
                        val msg = when {
                            allTracks.isNullOrEmpty() ->
                                "No audio files found.\nAdd music to /sdcard/Music, then reopen the app."
                            query.isNotBlank() -> "No results for \"$query\""
                            favOnly -> "No favorites yet.\nTap the heart next to a track to add."
                            folderFilter.isNotEmpty() -> "No tracks in \"$folderFilter\""
                            else -> "No tracks match the current filters."
                        }
                        CenterText(msg)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.id }) { t ->
                            ListItem(
                                headlineContent = { Text(t.title) },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(t.artist, t.album, t.folder,
                                                      formatDuration(t.durationMs))
                                            .joinToString(" - ")
                                    )
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { onToggleFavorite(t.id) }) {
                                            Icon(
                                                painter = painterResource(
                                                    if (t.id in favorites) R.drawable.ic_favorite_filled
                                                    else R.drawable.ic_favorite_border
                                                ),
                                                contentDescription = if (t.id in favorites)
                                                    "Remove from favorites"
                                                else "Add to favorites",
                                                tint = if (t.id in favorites)
                                                           MaterialTheme.colorScheme.primary
                                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { onDeleteRequest(t) }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_delete),
                                                contentDescription = "Delete track",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { onTrackClick(t) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun comparatorFor(mode: SortMode): Comparator<Track> = when (mode) {
    SortMode.TITLE -> Comparator { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
    SortMode.ARTIST -> Comparator { a, b ->
        val c = (a.artist ?: "\uFFFF").compareTo(b.artist ?: "\uFFFF", ignoreCase = true)
        if (c != 0) c else a.title.compareTo(b.title, ignoreCase = true)
    }
    SortMode.ALBUM -> Comparator { a, b ->
        val c = (a.album ?: "\uFFFF").compareTo(b.album ?: "\uFFFF", ignoreCase = true)
        if (c != 0) c else a.title.compareTo(b.title, ignoreCase = true)
    }
    SortMode.RECENT -> Comparator { a, b -> b.id.compareTo(a.id) }
    SortMode.DURATION -> Comparator { a, b -> b.durationMs.compareTo(a.durationMs) }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
