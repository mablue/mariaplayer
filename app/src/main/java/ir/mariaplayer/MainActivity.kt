package ir.mariaplayer

import androidx.compose.foundation.shape.RoundedCornerShape

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ir.mariaplayer.playback.LocalPlaybackRepository
import ir.mariaplayer.playback.rememberPlaybackRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import ir.mariaplayer.data.Collection
import ir.mariaplayer.data.CollectionRepository
import ir.mariaplayer.data.CollectionRule
import ir.mariaplayer.data.LibraryPrefs
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.SortMode
import ir.mariaplayer.data.Track
import ir.mariaplayer.playback.ACTION_TRACK_DELETED
import ir.mariaplayer.playback.EXTRA_DELETED_URI
import ir.mariaplayer.playback.NowPlayingBar
import ir.mariaplayer.playback.NowPlayingScreen
import ir.mariaplayer.playback.rememberPlayerController
import ir.mariaplayer.ui.MariaPlayerTheme
import ir.mariaplayer.ui.GlassTrackRow
import ir.mariaplayer.ui.AboutDialog
import ir.mariaplayer.ui.AddToPlaylistSheet
import ir.mariaplayer.ui.PermissionScreen
import ir.mariaplayer.ui.SelfTestScreen
import ir.mariaplayer.ui.LyricsDisplayScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

    var rawTracks by remember { mutableStateOf<List<Track>?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }

    LaunchedEffect(hasAudio) {
        if (hasAudio) rawTracks = LibraryScanner.scan(context)
    }

    val contentTypeOverrides by remember {
        ir.mariaplayer.data.ContentTypePrefs.observeAll(context)
    }.collectAsState(initial = emptyMap())

    val tracks: List<Track>? = remember(rawTracks, contentTypeOverrides) {
        rawTracks?.let {
            ir.mariaplayer.data.ContentTypeLogic.applyAll(it, contentTypeOverrides)
        }
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
                Intent(ACTION_TRACK_DELETED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_DELETED_URI, uri.toString())
            )
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ACTION_TRACK_DELETED) {
                    val deletedUri = i.getStringExtra(EXTRA_DELETED_URI)
                    if (deletedUri != null) {
                        rawTracks = rawTracks?.filterNot { it.contentUri == deletedUri }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_TRACK_DELETED)
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
    val librarySnapshot by repository.state.collectAsState()
    val libraryPlayingTrackId = librarySnapshot.trackId
    val libraryListState = rememberLazyListState()
    var lastAutoScrolledTid by remember { mutableStateOf<Long?>(null) }
    var shuffleOn by remember { mutableStateOf(controller?.shuffleModeEnabled ?: false) }
    var screen by rememberSaveable { mutableStateOf("library") }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var lyricsFromNowPlaying by rememberSaveable { mutableStateOf(false) }
    var pendingTrackForPlaylist by remember { mutableStateOf<Track?>(null) }

    // PR-L20-fix: system back button returns to library instead of
    // exiting the app. Handles every non-library screen here so
    // individual screens don't need their own BackHandler.
    BackHandler(enabled = screen != "library") {
        screen = "library"
    }
    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }

    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    var prefsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sortMode = LibraryPrefs.readSort(context)
        prefsLoaded = true
    }
    LaunchedEffect(sortMode, prefsLoaded) {
        if (prefsLoaded) LibraryPrefs.saveSort(context, sortMode)
    }

    var collections by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var trashedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedCollectionId by rememberSaveable { mutableStateOf(CollectionRepository.ALL_ID) }
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var collectionToEdit by remember { mutableStateOf<Collection?>(null) }
    LaunchedEffect(Unit) {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.ensureTrash(context)
        CollectionRepository.observe(context).collectLatest { collections = it }
    }
    LaunchedEffect(Unit) {
        CollectionRepository.observeTrashIds(context).collectLatest { trashedIds = it }
    }
    val selectedCollection = remember(collections, selectedCollectionId) {
        collections.firstOrNull { it.id == selectedCollectionId } ?: collections.firstOrNull()
    }
    val favorites: Set<Long> = remember(collections) {
        (collections.firstOrNull { it.id == CollectionRepository.FAVORITES_ID }?.rule as? CollectionRule.Manual)
            ?.trackIds?.toSet() ?: emptySet()
    }

    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf("") }

    val visible: List<Track>? = remember(tracks, query, sortMode, folderFilter, selectedCollection, trashedIds) {
        val base = tracks ?: return@remember null
        val inCollection = selectedCollection?.let { CollectionRepository.resolveTracks(base, it, trashedIds) }
            ?: base.filter { it.id !in trashedIds }
        val q = query.trim().lowercase()
        inCollection.asSequence()
            .filter { t ->
                (folderFilter.isEmpty() || t.folder == folderFilter) &&
                (q.isEmpty() ||
                    t.title.lowercase().contains(q) ||
                    t.artist?.lowercase()?.contains(q) == true ||
                    t.album?.lowercase()?.contains(q) == true)
            }
            .sortedWith(comparatorFor(sortMode))
            .toList()
    }

    LaunchedEffect(libraryPlayingTrackId, visible) {
        val tid = libraryPlayingTrackId ?: return@LaunchedEffect
        if (lastAutoScrolledTid == tid) return@LaunchedEffect
        val list = visible ?: return@LaunchedEffect
        val idx = list.indexOfFirst { it.id == tid }
        if (idx < 0) return@LaunchedEffect
        lastAutoScrolledTid = tid
        val info = libraryListState.layoutInfo
        val item = info.visibleItemsInfo.find { it.index == idx }
        if (item == null) {
            libraryListState.animateScrollToItem(idx)
        } else {
            val top = info.viewportStartOffset
            val bottom = info.viewportEndOffset
            val itemTop = item.offset
            val itemBottom = item.offset + item.size
            when {
                itemTop < top -> libraryListState.animateScrollToItem(idx)
                itemBottom > bottom -> {
                    val off = -(bottom - top - item.size)
                    libraryListState.animateScrollToItem(idx, off)
                }
            }
        }
    }

    LaunchedEffect(controller, tracks) {
        val c = controller ?: return@LaunchedEffect
        val list = tracks ?: return@LaunchedEffect
        if (c.mediaItemCount > 0) return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val saved = PlaybackPrefs.read(context)
        val idx = saved.trackId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx < 0) return@LaunchedEffect
        val pos = saved.trackId?.let { PlaybackPrefs.readPosition(context, it) }
            ?: saved.positionMs
        playQueue(c, list, idx, pos, autoPlay = false)
        shuffleOn = c.shuffleModeEnabled
    }

    if (showNewCollectionDialog) {
        NewCollectionDialog(
            onDismiss = { showNewCollectionDialog = false },
            onCreate = { name, rule ->
                scope.launch {
                    val id = CollectionRepository.create(context, name, rule)
                    selectedCollectionId = id
                }
                showNewCollectionDialog = false
            },
        )
    }
    collectionToEdit?.let { coll ->
        EditCollectionDialog(
            collection = coll,
            onDismiss = { collectionToEdit = null },
            onSave = { newName ->
                scope.launch { CollectionRepository.rename(context, coll.id, newName) }
                collectionToEdit = null
            },
            onDelete = {
                scope.launch {
                    CollectionRepository.delete(context, coll.id)
                    if (selectedCollectionId == coll.id) selectedCollectionId = CollectionRepository.ALL_ID
                }
                collectionToEdit = null
            },
        )
    }

    CompositionLocalProvider(LocalPlaybackRepository provides repository) {
    if (showNowPlaying) {
        NowPlayingScreen(
            controller = controller,
            onClose = { showNowPlaying = false },
            onLyricsClick = {
                lyricsFromNowPlaying = true
                showNowPlaying = false
                screen = "lyrics_display"
            },
        )
    } else {
    when {
        screen == "lyrics_display" -> {
            LyricsDisplayScreen(controller, onBack = {
                if (lyricsFromNowPlaying) {
                    lyricsFromNowPlaying = false
                    showNowPlaying = true
                }
                screen = "library"
            })
        }

        screen == "selftest" -> {
            SelfTestScreen(onBack = { screen = "library" })
        }

        else -> LibraryScreen(
        allTracks = tracks,
        visible = visible,
        listState = libraryListState,
        playingTrackId = libraryPlayingTrackId,
        scanning = tracks == null,
        controller = controller,
        shuffleOn = shuffleOn,
        sortMode = sortMode,
        onSortChange = { sortMode = it },
        query = query,
        onQueryChange = { query = it },
        folderFilter = folderFilter,
        onFolderFilterChange = { folderFilter = it },
        favorites = favorites,
        onToggleFavorite = { id -> scope.launch { CollectionRepository.toggleFavorite(context, id) } },
        onShuffleClick = {
            val c = controller ?: return@LibraryScreen
            val list = visible ?: return@LibraryScreen
            val pool = list
            if (c.mediaItemCount == 0 && pool.isNotEmpty()) {
                playQueue(c, pool, 0, 0L, autoPlay = true)
                c.shuffleModeEnabled = true
            } else {
                c.shuffleModeEnabled = !c.shuffleModeEnabled
            }
            shuffleOn = c.shuffleModeEnabled
        },
        onTrackClick = { t ->
            val c = controller ?: return@LibraryScreen
            val list = visible ?: return@LibraryScreen
            val sameType = list.filter { it.contentType == t.contentType }
            val idx = sameType.indexOfFirst { it.id == t.id }
            if (idx >= 0) playQueue(c, sameType, idx, 0L, autoPlay = true)
        },
        onTrackLongClick = { t -> pendingTrackForPlaylist = t },
        onLyricsClick = { screen = "lyrics_display" },
        onSelfTestClick = { screen = "selftest" },
        onNowPlayingExpand = { showNowPlaying = true },
        collections = collections,
        selectedCollectionId = selectedCollection?.id ?: "",
        onSelectCollection = { selectedCollectionId = it.id },
        onAddCollection = { showNewCollectionDialog = true },
        onEditCollection = { collectionToEdit = it },
        onDeleteRequest = { t ->
            scope.launch {
                CollectionRepository.trash(context, t.id)
                Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
            }
        },
        onRestoreRequest = { t ->
            scope.launch {
                CollectionRepository.restore(context, t.id)
                Toast.makeText(context, "Restored", Toast.LENGTH_SHORT).show()
            }
        },
    )
    }
    }

    pendingTrackForPlaylist?.let { pending ->
        AddToPlaylistSheet(listOf(pending), scope) { pendingTrackForPlaylist = null }
    }
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
    listState: LazyListState,
    playingTrackId: Long?,
    scanning: Boolean,
    controller: MediaController?,
    shuffleOn: Boolean,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    folderFilter: String,
    onFolderFilterChange: (String) -> Unit,
    favorites: Set<Long>,
    onToggleFavorite: (Long) -> Unit,
    onShuffleClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onDeleteRequest: (Track) -> Unit,
    onRestoreRequest: (Track) -> Unit,
    onTrackLongClick: (Track) -> Unit,
    onLyricsClick: () -> Unit,
    onSelfTestClick: () -> Unit,
    onNowPlayingExpand: () -> Unit,
    collections: List<Collection>,
    selectedCollectionId: String,
    onSelectCollection: (Collection) -> Unit,
    onAddCollection: () -> Unit,
    onEditCollection: (Collection) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkAddDialog by remember { mutableStateOf(false) }
    var showTrackTypeSheet by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val bulkDeleteLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selected = visible?.filter { it.id in selectedIds } ?: emptyList()
            for (t in selected) {
                context.sendBroadcast(
                    android.content.Intent(ir.mariaplayer.playback.ACTION_TRACK_DELETED)
                        .setPackage(context.packageName)
                        .putExtra(ir.mariaplayer.playback.EXTRA_DELETED_URI, t.contentUri)
                )
            }
            android.widget.Toast.makeText(
                context, "Deleted ${selected.size}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            selectionMode = false
            selectedIds = emptySet()
        }
    }

    if (showBulkDeleteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { androidx.compose.material3.Text("Move ${selectedIds.size} to Trash?") },
            text = { androidx.compose.material3.Text("You can restore any track from the Trash tab.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val selected = visible?.filter { it.id in selectedIds } ?: emptyList()
                    scope.launch {
                        for (t in selected) CollectionRepository.trash(context, t.id)
                        Toast.makeText(
                            context, "Moved ${selected.size} to Trash",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    selectionMode = false
                    selectedIds = emptySet()
                    showBulkDeleteDialog = false
                }) { androidx.compose.material3.Text("Trash") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBulkDeleteDialog = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }
    if (showTrackTypeSheet) {
        ir.mariaplayer.ui.TrackTypeSheet(
            trackIds = selectedIds.toList(),
            onDismiss = {
                showTrackTypeSheet = false
                selectionMode = false
                selectedIds = emptySet()
            },
        )
    }

    if (showBulkAddDialog) {
        val selectedTracks = visible?.filter { it.id in selectedIds } ?: emptyList()
        if (selectedTracks.isNotEmpty()) {
            AddToPlaylistSheet(selectedTracks, scope) {
                showBulkAddDialog = false
                selectionMode = false
                selectedIds = emptySet()
            }
        } else {
            showBulkAddDialog = false
        }
    }

    if (showAbout) {
        AboutDialog { showAbout = false }
    }

    val folderList = remember(allTracks) {
        allTracks?.map { it.folder }?.filter { it.isNotEmpty() }?.distinct()?.sorted()
            ?: emptyList()
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete selected")
                        }
                        IconButton(onClick = { showBulkAddDialog = true }) {
                            Icon(painter = painterResource(R.drawable.ic_playlist),
                                contentDescription = "Add to playlist")
                        }
                        IconButton(onClick = {
                            val selected = visible?.filter { it.id in selectedIds } ?: emptyList()
                            if (selected.isNotEmpty()) {
                                val uris = ArrayList(selected.map { android.net.Uri.parse(it.contentUri) })
                                val share = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "audio/*"
                                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(share, "Share ${selected.size} tracks")
                                )
                                selectionMode = false
                                selectedIds = emptySet()
                            }
                        }) {
                            Icon(painter = painterResource(R.drawable.ic_share),
                                contentDescription = "Share selected")
                        }
                        IconButton(onClick = { showTrackTypeSheet = true }) {
                            Icon(painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Change type")
                        }
                    }
                )
            } else {
            TopAppBar(
                navigationIcon = {
                    if (searchActive) {
                        IconButton(onClick = {
                            searchActive = false
                            onQueryChange("")
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Close search",
                            )
                        }
                    } else {
                        IconButton(onClick = { showAbout = true }) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = "About",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                },
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            placeholder = {
                                Text("Search title, artist, album",
                                    style = MaterialTheme.typography.bodyMedium)
                            },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp),
                        )
                    }
                },
                actions = {
                    val sz = Modifier.size(36.dp)
                    val isz = Modifier.size(22.dp)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    IconButton(onClick = { searchActive = true }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Search", modifier = isz)
                    }
                    IconButton(onClick = { onSelfTestClick() }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_self_test),
                            contentDescription = "Self-test", modifier = isz)
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
                    IconButton(onClick = { onShuffleClick() }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_shuffle),
                            contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off",
                            tint = if (shuffleOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = isz)
                    }
                    }
                },
            )
            }
        },
        bottomBar = { NowPlayingBar(controller, onLyricsClick, onExpand = onNowPlayingExpand) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            ir.mariaplayer.ui.CollectionBar(
                collections = collections,
                selectedId = selectedCollectionId,
                onSelect = onSelectCollection,
                onAdd = onAddCollection,
                onLongPress = onEditCollection,
            )

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    scanning -> CenterText("Scanning library...")
                    visible.isNullOrEmpty() -> {
                        val isFavTab = selectedCollectionId == CollectionRepository.FAVORITES_ID
                        val msg = when {
                            allTracks.isNullOrEmpty() ->
                                "No audio files found.\nAdd music to /sdcard/Music, then reopen the app."
                            query.isNotBlank() -> "No results for \"$query\""
                            isFavTab -> "No favorites yet.\nTap the heart next to a track to add."
                            folderFilter.isNotEmpty() -> "No tracks in \"$folderFilter\""
                            else -> "No tracks match the current filters."
                        }
                        CenterText(msg)
                    }
                    else -> {
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
                        // auto-scroll moved to AppRoot (survives navigation)
                        // idle poller removed (auto-scroll handled in AppRoot)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
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
                                    restoreMode = selectedCollectionId == CollectionRepository.TRASH_ID,
                                    onRestoreClick = { onRestoreRequest(t) },
                                    onClick = {
                                        if (selectionMode) {
                                            val next = if (t.id in selectedIds) selectedIds - t.id else selectedIds + t.id
                                            selectedIds = next
                                            if (next.isEmpty()) selectionMode = false
                                        } else onTrackClick(t)
                                    },
                                    onLongClick = {
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedIds = setOf(t.id)
                                        } else {
                                            val next = if (t.id in selectedIds) selectedIds - t.id else selectedIds + t.id
                                            selectedIds = next
                                            if (next.isEmpty()) selectionMode = false
                                        }
                                    },
                                    isActive = t.id == playingTrackId,
                                    isSelected = t.id in selectedIds,
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


@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun NewCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, CollectionRule) -> Unit,
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("manual") }
    var regexPattern by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New collection") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                Text("Type:", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "manual" to "Manual",
                        "under10" to "<10m",
                        "10m2h" to "10m-2h",
                        "over2h" to "2h+",
                        "regex" to "Regex",
                    ).forEach { (m, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = mode == m, onClick = { mode = m },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                if (mode == "regex") {
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = regexPattern, onValueChange = { regexPattern = it },
                        singleLine = true, label = { Text("Pattern") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = name.isNotBlank() && (mode != "regex" || regexPattern.isNotBlank()),
                onClick = {
                    val rule: CollectionRule = when (mode) {
                        "manual" -> CollectionRule.Manual(emptyList())
                        "under10" -> CollectionRule.Duration(maxMs = 10 * 60_000L)
                        "10m2h" -> CollectionRule.Duration(minMs = 10 * 60_000L, maxMs = 2 * 60 * 60_000L)
                        "over2h" -> CollectionRule.Duration(minMs = 2L * 60 * 60_000L)
                        "regex" -> CollectionRule.Pattern(regexPattern)
                        else -> CollectionRule.Manual(emptyList())
                    }
                    onCreate(name.trim(), rule)
                },
            ) { Text("Create") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun EditCollectionDialog(
    collection: Collection,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editedName by androidx.compose.runtime.remember(collection.id) {
        androidx.compose.runtime.mutableStateOf(collection.label)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit collection") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = editedName, onValueChange = { editedName = it },
                    singleLine = true, label = { Text("Name") },
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                Text(
                    "Rule: ${describeCollectionRule(collection.rule)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = editedName.isNotBlank(),
                onClick = { onSave(editedName.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (collection.id != CollectionRepository.ALL_ID) {
                    androidx.compose.material3.TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun describeCollectionRule(rule: CollectionRule): String = when (rule) {
    is CollectionRule.All -> "All tracks"
    is CollectionRule.Manual -> "${rule.trackIds.size} tracks"
    is CollectionRule.Duration -> {
        val min = rule.minMs?.let { "${it / 60_000}m" } ?: "0"
        val max = rule.maxMs?.let { "${it / 60_000}m" } ?: "inf"
        "Duration $min to $max"
    }
    is CollectionRule.Types -> "Types: ${rule.types.joinToString { it.name }}"
    is CollectionRule.Pattern -> "Regex: ${rule.pattern}"
}
