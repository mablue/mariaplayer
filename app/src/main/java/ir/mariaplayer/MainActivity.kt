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
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
import ir.mariaplayer.ui.GlassTrackRow
import ir.mariaplayer.ui.AddToPlaylistSheet
import ir.mariaplayer.ui.PermissionScreen
import ir.mariaplayer.ui.PlaylistDetailScreen
import ir.mariaplayer.ui.PlaylistsScreen
import ir.mariaplayer.ui.SelfTestScreen
import ir.mariaplayer.ui.LyricsDisplayScreen
import kotlinx.coroutines.Dispatchers
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

    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }

    LaunchedEffect(hasAudio) {
        if (hasAudio) tracks = LibraryScanner.scan(context)
    }


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
                    val deletedUri = i.getStringExtra(
                        ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI
                    )
                    if (deletedUri != null) {
                        tracks = tracks?.filterNot { it.contentUri == deletedUri }
                    }
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

    // tracks populated by LaunchedEffect above; deleted items removed in receiver
    val controller = rememberPlayerController()
    val repository = rememberPlaybackRepository(controller)
    var shuffleOn by remember { mutableStateOf(controller?.shuffleModeEnabled ?: false) }
    var screen by rememberSaveable { mutableStateOf("library") }
    var pendingTrackForPlaylist by remember { mutableStateOf<Track?>(null) }

    // PR-L20-fix: system back button returns to library instead of
    // exiting the app. Handles every non-library screen here so
    // individual screens don't need their own BackHandler.
    BackHandler(enabled = screen != "library") {
        screen = when {
            screen.startsWith("playlist:") -> "playlists"
            else -> "library"
        }
    }

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

    // PR-L58: resume per-track — save on 500ms tick + key events,
    // restore new track from per-track map on transition, clear on ENDED.
    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        var prevId: Long? = c.currentMediaItem?.mediaId?.toLongOrNull()
        var prevPos: Long = c.currentPosition

        val timerJob = scope.launch {
            while (true) {
                delay(500)
                if (c.playbackState == Player.STATE_ENDED) continue
                val mi = c.currentMediaItem ?: continue
                val tid = mi.mediaId.toLongOrNull() ?: continue
                val pos = c.currentPosition
                prevPos = pos
                PlaybackPrefs.save(context, tid, pos)
            }
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val mi = c.currentMediaItem ?: return
                val tid = mi.mediaId.toLongOrNull() ?: return
                prevPos = c.currentPosition
                scope.launch {
                    PlaybackPrefs.save(context, tid, c.currentPosition)
                    PlaybackPrefs.savePosition(context, tid, c.currentPosition)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                val mi = c.currentMediaItem ?: return
                val tid = mi.mediaId.toLongOrNull() ?: return
                when (state) {
                    Player.STATE_READY -> {
                        prevPos = c.currentPosition
                        scope.launch {
                            PlaybackPrefs.save(context, tid, c.currentPosition)
                            PlaybackPrefs.savePosition(context, tid, c.currentPosition)
                        }
                    }
                    Player.STATE_ENDED -> {
                        prevPos = 0L
                        scope.launch {
                            PlaybackPrefs.clearPosition(context, tid)
                            PlaybackPrefs.save(context, tid, 0L)
                        }
                    }
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                prevPos = c.currentPosition
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val oldId = prevId
                val oldPos = prevPos
                when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                        if (oldId != null) {
                            scope.launch {
                                PlaybackPrefs.clearPosition(context, oldId)
                                PlaybackPrefs.save(context, oldId, 0L)
                            }
                        }
                    }
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                        if (oldId != null && oldPos > 5_000L) {
                            scope.launch {
                                PlaybackPrefs.savePosition(context, oldId, oldPos)
                            }
                        }
                    }
                    else -> { /* playlist changed — ignore */ }
                }
                val newId = mediaItem?.mediaId?.toLongOrNull()
                if (newId != null && newId != oldId) {
                    scope.launch {
                        val saved = PlaybackPrefs.readPosition(context, newId)
                        if (saved != null && saved > 5_000L) {
                            c.seekTo(saved)
                        }
                    }
                }
                prevId = newId
                prevPos = c.currentPosition
            }
        }
        c.addListener(listener)
        onDispose {
            timerJob.cancel()
            val tid = c.currentMediaItem?.mediaId?.toLongOrNull()
            if (tid != null) {
                val pos = c.currentPosition
                scope.launch {
                    PlaybackPrefs.save(context, tid, pos)
                    PlaybackPrefs.savePosition(context, tid, pos)
                }
            }
            c.removeListener(listener)
        }
    }

    CompositionLocalProvider(LocalPlaybackRepository provides repository) {
    when {
        screen == "lyrics_display" -> {
            LyricsDisplayScreen(controller, onBack = { screen = "library" })
        }
        screen == "playlists" -> {
            PlaylistsScreen(
                onBack = { screen = "library" },
                onOpenPlaylist = { id -> screen = "playlist:$id" },
                controller = controller,
                onLyricsClick = { screen = "lyrics_display" },
            )
        }
        screen == "selftest" -> {
            SelfTestScreen(onBack = { screen = "library" })
        }
        screen.startsWith("playlist:") -> {
            val pid = screen.removePrefix("playlist:").toLongOrNull()
            if (pid == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) { screen = "playlists" }
            } else {
                PlaylistDetailScreen(
                    playlistId = pid,
                    allTracks = tracks ?: emptyList(),
                    controller = controller,
                    onPlayTrack = { list, idx ->
                        playQueue(controller, list, idx, 0L, autoPlay = true)
                    },
                    onBack = { screen = "playlists" },
                    onLyricsClick = { screen = "lyrics_display" },
                )
            }
        }
        else -> LibraryScreen(
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
        onTrackLongClick = { t -> pendingTrackForPlaylist = t },
        onLyricsClick = { screen = "lyrics_display" },
        onPlaylistsClick = { screen = "playlists" },
        onSelfTestClick = { screen = "selftest" },
        onDeleteRequest = { t ->
            val uri = Uri.parse(t.contentUri)
            if (Build.VERSION.SDK_INT >= 30) {
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

    pendingTrackForPlaylist?.let { pending ->
        AddToPlaylistSheet(pending) { pendingTrackForPlaylist = null }
    }
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
                    .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                    .setExtras(Bundle().apply {
                        putString("filename", t.filename)
                        putString("folder", t.folder)
                        putLong("durationMs", t.durationMs)
                    })
                    .build()
            )
            .build()
    }
    c.setMediaItems(items, startIndex, startPositionMs)
    c.repeatMode = Player.REPEAT_MODE_ALL
    c.prepare()
    if (autoPlay) c.play()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onTrackLongClick: (Track) -> Unit,
    onLyricsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onSelfTestClick: () -> Unit,
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
                    val sz = Modifier.size(36.dp)
                    val isz = Modifier.size(22.dp)
                    IconButton(onClick = { onSelfTestClick() }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_self_test),
                            contentDescription = "Self-test", modifier = isz)
                    }
                    IconButton(onClick = { onPlaylistsClick() }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_playlist),
                            contentDescription = "Playlists", modifier = isz)
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }, modifier = sz) {
                            Icon(painter = painterResource(R.drawable.ic_sort),
                                contentDescription = "Sort by ${sortMode.label}",
                                modifier = isz)
                        }
                        DropdownMenu(expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                            modifier = Modifier.heightIn(max = 400.dp)) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(text = { Text(m.label) },
                                    onClick = { onSortChange(m); sortMenuOpen = false })
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { folderMenuOpen = true }, modifier = sz) {
                            Icon(painter = painterResource(R.drawable.ic_folder),
                                contentDescription = if (folderFilter.isEmpty())
                                    "Folder filter: all" else "Folder filter: $folderFilter",
                                tint = if (folderFilter.isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = isz)
                        }
                        DropdownMenu(expanded = folderMenuOpen,
                            onDismissRequest = { folderMenuOpen = false },
                            modifier = Modifier.heightIn(max = 400.dp)) {
                            DropdownMenuItem(text = { Text("All folders") },
                                onClick = { onFolderFilterChange(""); folderMenuOpen = false })
                            folderList.forEach { f ->
                                DropdownMenuItem(text = { Text(f) },
                                    onClick = { onFolderFilterChange(f); folderMenuOpen = false })
                            }
                        }
                    }
                    IconButton(onClick = { onFavOnlyChange(!favOnly) }, modifier = sz) {
                        Icon(painter = painterResource(
                                if (favOnly) R.drawable.ic_favorite_filled
                                else R.drawable.ic_favorite_border),
                            contentDescription = if (favOnly) "Favorites filter on"
                                else "Favorites filter off",
                            tint = if (favOnly) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = isz)
                    }
                    IconButton(onClick = { onShuffleClick() }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_shuffle),
                            contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off",
                            tint = if (shuffleOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = isz)
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar(controller, onLyricsClick) },
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
                    else -> {
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        val sections = remember(visible, sortMode) {
                            val list = visible
                            if (list == null ||
                                sortMode == SortMode.RECENT ||
                                sortMode == SortMode.DURATION
                            ) {
                                emptyList()
                            } else {
                                val keyOf: (Track) -> Char = when (sortMode) {
                                    SortMode.TITLE -> { t -> t.title.firstOrNull()?.uppercaseChar() ?: '#' }
                                    SortMode.ARTIST -> { t -> (t.artist ?: "").firstOrNull()?.uppercaseChar() ?: '#' }
                                    SortMode.ALBUM -> { t -> (t.album ?: "").firstOrNull()?.uppercaseChar() ?: '#' }
                                    else -> { _ -> '#' }
                                }
                                val firsts = LinkedHashMap<Char, Int>()
                                list.forEachIndexed { idx, t ->
                                    val c = keyOf(t)
                                    if (!firsts.containsKey(c)) firsts[c] = idx
                                }
                                firsts.entries
                                    .sortedWith(compareBy({ if (it.key == '#') 0 else 1 }, { it.key }))
                                    .map { it.key.toString() to it.value }
                            }
                        }
                        var bubbleLetter by remember { mutableStateOf<String?>(null) }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visible, key = { it.id }) { t ->
                                GlassTrackRow(
                                    title = t.title,
                                    subtitle = listOfNotNull(
                                        t.artist, t.album, t.folder,
                                        formatDuration(t.durationMs),
                                    ).joinToString(" · "),
                                    isFavorite = t.id in favorites,
                                    onFavClick = { onToggleFavorite(t.id) },
                                    onDeleteClick = { onDeleteRequest(t) },
                                    onClick = { onTrackClick(t) },
                                    onLongClick = { onTrackLongClick(t) },
                                    track = t,
                                )
                            }
                        }
                        AlphabetIndex(
                            sections = sections,
                            onLetterSelected = { index ->
                                scope.launch { listState.scrollToItem(index) }
                            },
                            onLetterChange = { bubbleLetter = it },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                        bubbleLetter?.let { letter ->
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(72.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    letter,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
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

@Composable
private fun AlphabetIndex(
    sections: List<Pair<String, Int>>,
    onLetterSelected: (Int) -> Unit,
    onLetterChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) return
    val currentSelected by rememberUpdatedState(onLetterSelected)
    val currentChange by rememberUpdatedState(onLetterChange)
    Box(
        modifier = modifier
            .width(20.dp)
            .fillMaxHeight()
            .pointerInput(sections) {
                val itemHeightPx = size.height.toFloat() / sections.size.coerceAtLeast(1)
                fun pick(y: Float): Int =
                    (y / itemHeightPx).toInt().coerceIn(0, sections.lastIndex)
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var idx = pick(down.position.y)
                        currentChange(sections[idx].first)
                        currentSelected(sections[idx].second)
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    idx = pick(change.position.y)
                                    currentChange(sections[idx].first)
                                    currentSelected(sections[idx].second)
                                    change.consume()
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        currentChange(null)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            sections.forEach { (letter, _) ->
                Text(
                    text = letter,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}
