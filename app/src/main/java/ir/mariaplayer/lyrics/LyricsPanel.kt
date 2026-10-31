package ir.mariaplayer.lyrics

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

private sealed interface LyricsState {
    data object Empty : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<LrcLine>) : LyricsState
    data class Plain(val text: String) : LyricsState
    data object NotFound : LyricsState
    data class NetworkError(val msg: String) : LyricsState
    data object Timeout : LyricsState
    data object PasteSheet : LyricsState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsPanel(
    controller: MediaController?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val item = controller?.currentMediaItem
    val mediaId = item?.mediaId
    val trackId = mediaId?.toLongOrNull()
    val title    = item?.mediaMetadata?.title?.toString().orEmpty()
    val artist   = item?.mediaMetadata?.artist?.toString().orEmpty()
    val filename = item?.mediaMetadata?.extras?.getString("filename").orEmpty()
    val durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L

    // Reset on track change; trigger fires the fetch.
    var state by remember(mediaId) { mutableStateOf<LyricsState>(LyricsState.Empty) }
    var trigger by remember(mediaId) { mutableStateOf(0) }
    var offsetMs by remember(mediaId) { mutableLongStateOf(0L) }

    // PR-L6a: editable query fields, prefilled from media metadata.
    // User can correct broken tags and retry without leaving the sheet.
    var editTitle by remember(mediaId) { mutableStateOf(title) }
    var editArtist by remember(mediaId) { mutableStateOf(artist) }

    // Load offset (and cached lyrics, if any) when track changes.
    LaunchedEffect(mediaId, trackId) {
        val id = trackId ?: return@LaunchedEffect
        val cached = LyricsCache.read(context, id)
        offsetMs = cached.offsetMs
        // Only auto-populate from cache if there IS cached lyrics
        if (cached.hasLyrics) {
            state = toState(cached.synced, cached.plain)
        }
    }

    // Fetch — keyed on (mediaId, trigger). Track change cancels mid-flight.
    LaunchedEffect(mediaId, trigger) {
        if (trigger == 0) return@LaunchedEffect
        val id = trackId ?: run { state = LyricsState.NotFound; return@LaunchedEffect }
        state = LyricsState.Loading
        val r = LyricsFetcher().fetch(
            editTitle, editArtist, filename, (durationMs / 1000).toInt(),
        )
        state = when (r) {
            is LyricsFetchResult.Found -> {
                LyricsCache.writeLyrics(
                    context, id, r.result.syncedLyrics, r.result.plainLyrics,
                )
                toState(r.result.syncedLyrics, r.result.plainLyrics)
            }
            LyricsFetchResult.NotFound -> LyricsState.NotFound
            is LyricsFetchResult.NetworkError -> LyricsState.NetworkError(r.message)
            LyricsFetchResult.Timeout -> LyricsState.Timeout
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = editTitle,
                onValueChange = { editTitle = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = editArtist,
                onValueChange = { editArtist = it },
                label = { Text("Artist (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    enabled = editTitle.isNotBlank() && state !is LyricsState.Loading,
                    onClick = { trigger += 1 },
                ) { Text("Search") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        editTitle = title
                        editArtist = artist
                    },
                ) { Text("Reset") }
            }
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                LyricsState.Empty -> {
                    Text(
                        "Edit title/artist if needed, then tap Search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                LyricsState.Loading -> CircularProgressIndicator()

                is LyricsState.Synced -> {
                    SyncedLyrics(controller, s.lines, offsetMs)
                    Spacer(Modifier.height(8.dp))
                    OffsetControl(
                        current = offsetMs,
                        onChange = { newOffset ->
                            offsetMs = newOffset
                            trackId?.let {
                                scope.launch { LyricsCache.writeOffset(context, it, newOffset) }
                            }
                        },
                    )
                    ActionRow(
                        onRefresh = {
                            trackId?.let { id ->
                                scope.launch {
                                    LyricsCache.clearLyrics(context, id)
                                    trigger += 1
                                }
                            }
                        },
                    )
                }

                is LyricsState.Plain -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 500.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        Text(s.text,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }
                    ActionRow(
                        onRefresh = {
                            trackId?.let { id ->
                                scope.launch {
                                    LyricsCache.clearLyrics(context, id)
                                    trigger += 1
                                }
                            }
                        },
                    )
                }

                LyricsState.NotFound -> {
                    MessageBlock(
                        "No lyrics found.",
                        "Try searching Google, or paste lyrics you find.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { trigger += 1 }) { Text("Retry") }
                        TextButton(onClick = {
                            val q = URLEncoder.encode("$title $artist lyrics", "UTF-8")
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=$q")))
                        }) { Text("Google") }
                        TextButton(onClick = { state = LyricsState.PasteSheet }) {
                            Text("Paste")
                        }
                    }
                }

                is LyricsState.NetworkError -> {
                    MessageBlock("Network error.", s.msg)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { trigger += 1 }) { Text("Retry") }
                    }
                }

                LyricsState.Timeout -> {
                    MessageBlock(
                        "Request timed out.",
                        "Check your internet connection and try again.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { trigger += 1 }) { Text("Retry") }
                    }
                }
                LyricsState.PasteSheet -> {
                    var pasteText by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            label = { Text("Paste lyrics here") },
                            minLines = 6,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(onClick = { state = LyricsState.NotFound }) {
                                Text("Cancel")
                            }
                            Button(
                                enabled = pasteText.isNotBlank(),
                                onClick = {
                                    trackId?.let { id ->
                                        scope.launch {
                                            LyricsCache.writeLyrics(context, id, null, pasteText)
                                            state = LyricsState.Plain(pasteText)
                                        }
                                    }
                                },
                            ) { Text("Save") }
                        }
                    }
                }

            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun toState(synced: String?, plain: String?): LyricsState {
    if (!synced.isNullOrBlank()) {
        val lines = LrcParser.parse(synced)
        if (lines.isNotEmpty()) return LyricsState.Synced(lines)
    }
    if (!plain.isNullOrBlank()) return LyricsState.Plain(plain)
    return LyricsState.NotFound
}

@Composable
private fun ActionRow(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun OffsetControl(current: Long, onChange: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Offset", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text(
                "${if (current >= 0) "+" else ""}${current}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onChange(0L) }) { Text("Reset") }
        }
        Slider(
            value = current.toFloat(),
            onValueChange = { onChange(it.toLong().coerceIn(-500L, 500L)) },
            valueRange = -500f..500f,
            steps = 19,
        )
    }
}

@Composable
private fun MessageBlock(headline: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(headline, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun SyncedLyrics(controller: MediaController?, lines: List<LrcLine>, offsetMs: Long) {
    val listState = rememberLazyListState()
    var positionMs by remember { mutableLongStateOf(controller?.currentPosition ?: 0L) }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(500)
            positionMs = c.currentPosition
        }
    }

    val active = LrcParser.activeIndex(lines, positionMs + offsetMs)
    LaunchedEffect(active) {
        if (active >= 0) {
            try { listState.animateScrollToItem(active, scrollOffset = -200) } catch (_: Exception) {}
        }
    }

    Box(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { Spacer(Modifier.height(120.dp)) }
            itemsIndexed(lines) { idx, line ->
                val isActive = idx == active
                Text(
                    line.text.ifBlank { "♪" },
                    style = if (isActive) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyMedium,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            item { Spacer(Modifier.height(160.dp)) }
        }
    }
}
