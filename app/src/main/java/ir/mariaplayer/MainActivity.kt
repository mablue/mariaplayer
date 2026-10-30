package ir.mariaplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
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

    val tracks by produceState<List<Track>?>(initialValue = null, hasAudio) {
        value = LibraryScanner.scan(context)
    }
    val controller = rememberPlayerController()
    val scope = rememberCoroutineScope()
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

    // Resume last queue+position
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
        onToggleFavorite = { id ->
            scope.launch { FavoritesPrefs.toggle(context, id) }
        },
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
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }

    val folderList = remember(allTracks) {
        allTracks?.map { it.folder }?.filter { it.isNotEmpty() }?.distinct()?.sorted() ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MariaPlayer") },
                actions = {
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Text("Sort: ${sortMode.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = { onSortChange(m); sortMenuOpen = false },
                                )
                            }
                        }
                    }
                    TextButton(onClick = onShuffleClick) {
                        Text(if (shuffleOn) "Shuffle ON" else "Shuffle")
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
                        TextButton(onClick = { onQueryChange("") }) { Text("Clear") }
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = { onFavOnlyChange(!favOnly) }) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            if (favOnly) R.drawable.ic_favorite_filled
                            else R.drawable.ic_favorite_border
                        ),
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (favOnly) "Favorites ON" else "Favorites")
                }
                Box {
                    TextButton(onClick = { folderMenuOpen = true }) {
                        Text(
                            if (folderFilter.isEmpty()) "Folder: All"
                            else "Folder: $folderFilter",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = folderMenuOpen, onDismissRequest = { folderMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("All") },
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
                    TextButton(onClick = { onFolderFilterChange("") }) { Text("Clear") }
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
                            favOnly -> "No favorites yet.\nTap ♡ next to a track to add."
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
                                        listOfNotNull(t.artist, t.album, t.folder, formatDuration(t.durationMs))
                                            .joinToString(" - ")
                                    )
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { onToggleFavorite(t.id) }) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(
                                                    if (t.id in favorites) R.drawable.ic_favorite_filled
                                                    else R.drawable.ic_favorite_border
                                                ),
                                                contentDescription = if (t.id in favorites) "Unfavorite" else "Favorite",
                                                tint = if (t.id in favorites) MaterialTheme.colorScheme.primary
                                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { onDeleteRequest(t) }) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_delete),
                                                contentDescription = "Delete",
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
