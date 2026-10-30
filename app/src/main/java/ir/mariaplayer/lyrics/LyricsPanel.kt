package ir.mariaplayer.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface LyricsState {
    data object Empty : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<LrcLine>) : LyricsState
    data class Plain(val text: String) : LyricsState
    data object NotFound : LyricsState
    data class NetworkError(val msg: String) : LyricsState
    data object Timeout : LyricsState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsPanel(
    controller: MediaController?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val item = controller?.currentMediaItem
    val title    = item?.mediaMetadata?.title?.toString().orEmpty()
    val artist   = item?.mediaMetadata?.artist?.toString().orEmpty()
    val filename = item?.mediaMetadata?.extras?.getString("filename").orEmpty()
    val durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L

    var state by remember { mutableStateOf<LyricsState>(LyricsState.Empty) }

    // Reset when track changes
    LaunchedEffect(item?.mediaId) { state = LyricsState.Empty }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title.ifBlank { "Unknown title" },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
            if (artist.isNotBlank()) {
                Text(artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                LyricsState.Empty -> Button(onClick = {
                    state = LyricsState.Loading
                    scope.launch {
                        val r = LyricsFetcher().fetch(
                            title, artist, filename, (durationMs / 1000).toInt(),
                        )
                        state = when (r) {
                            is LyricsFetchResult.Found -> {
                                val synced = r.result.syncedLyrics
                                val plain  = r.result.plainLyrics
                                if (!synced.isNullOrBlank()) {
                                    val lines = LrcParser.parse(synced)
                                    if (lines.isNotEmpty()) LyricsState.Synced(lines)
                                    else LyricsState.Plain(plain ?: "(empty lyrics)")
                                } else if (!plain.isNullOrBlank()) {
                                    LyricsState.Plain(plain)
                                } else {
                                    LyricsState.NotFound
                                }
                            }
                            LyricsFetchResult.NotFound -> LyricsState.NotFound
                            is LyricsFetchResult.NetworkError -> LyricsState.NetworkError(r.message)
                            LyricsFetchResult.Timeout -> LyricsState.Timeout
                        }
                    }
                }) { Text("Search Lyrics") }

                LyricsState.Loading -> {
                    CircularProgressIndicator()
                }

                is LyricsState.Synced -> {
                    SyncedLyrics(controller, s.lines)
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
                }

                LyricsState.NotFound -> {
                    MessageBlock(
                        "No lyrics found on LRCLIB.",
                        "Try a different track, or fix the title in your library.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { state = LyricsState.Empty }) { Text("Retry") }
                }

                is LyricsState.NetworkError -> {
                    MessageBlock(
                        "Network error.",
                        s.msg,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { state = LyricsState.Empty }) { Text("Retry") }
                }

                LyricsState.Timeout -> {
                    MessageBlock(
                        "Request timed out.",
                        "Check your internet connection and try again.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { state = LyricsState.Empty }) { Text("Retry") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
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
private fun SyncedLyrics(controller: MediaController?, lines: List<LrcLine>) {
    val listState = rememberLazyListState()
    var positionMs by remember { mutableStateOf(controller?.currentPosition ?: 0L) }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(500)
            positionMs = c.currentPosition
        }
    }

    val active = LrcParser.activeIndex(lines, positionMs)
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
