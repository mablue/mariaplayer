package ir.mariaplayer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import ir.mariaplayer.R
import ir.mariaplayer.asr.AsrManager
import ir.mariaplayer.lyrics.LrcLine
import ir.mariaplayer.lyrics.LrcParser
import ir.mariaplayer.lyrics.LyricsCache
import ir.mariaplayer.lyrics.LyricsFetchResult
import ir.mariaplayer.lyrics.LyricsFetcher
import ir.mariaplayer.asr.AsrModels
import ir.mariaplayer.translate.TranslationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

private sealed interface DispState {
    data object Editing : DispState
    data object Loading : DispState
    data class Transcribing(val progress: Float, val status: String) : DispState
    data class Synced(val lines: List<LrcLine>) : DispState
    data class Plain(val text: String) : DispState
    data object NotFound : DispState
    data class Error(val msg: String) : DispState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsDisplayScreen(
    controller: MediaController?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val item = controller?.currentMediaItem
    val mediaId = item?.mediaId
    val trackId = mediaId?.toLongOrNull()
    val rawTitle = item?.mediaMetadata?.title?.toString().orEmpty()
    val rawArtist = item?.mediaMetadata?.artist?.toString().orEmpty()
    val filename = item?.mediaMetadata?.extras?.getString("filename").orEmpty()
    val folder = item?.mediaMetadata?.extras?.getString("folder").orEmpty()
    val contentUri = item?.localConfiguration?.uri
    val durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L

    var prefs by remember { mutableStateOf(DisplayPrefs()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var state by remember(mediaId) { mutableStateOf<DispState>(DispState.Editing) }
    var trigger by remember(mediaId) { mutableStateOf(0) }
    var transcribeTrigger by remember(mediaId) { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(controller?.isPlaying ?: false) }
    var albumArt by remember(mediaId) { mutableStateOf<Bitmap?>(null) }
    var translations by remember(mediaId) { mutableStateOf<List<String>?>(null) }
    var translating by remember(mediaId) { mutableStateOf(false) }

    var editTitle by remember(mediaId) { mutableStateOf(rawTitle) }
    var editArtist by remember(mediaId) { mutableStateOf(rawArtist) }

    var availableModels by remember { mutableStateOf<List<AsrModels.AvailableAsr>>(emptyList()) }
    var selectedModelId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        prefs = LyricsDisplayPrefs.read(context)
        availableModels = AsrModels.list(context)
        val saved = AsrModels.selected(context)
        selectedModelId = if (saved.isNotBlank() && availableModels.any { it.id == saved })
            saved
        else
            availableModels.firstOrNull()?.id.orEmpty()
    }

    LaunchedEffect(mediaId, contentUri) {
        albumArt = if (contentUri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, contentUri)
                    val raw = mmr.embeddedPicture ?: return@runCatching null
                    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        ?: return@runCatching null
                    val scaled = Bitmap.createScaledBitmap(bmp, 160, 160, true)
                    if (scaled !== bmp) bmp.recycle()
                    scaled
                } finally {
                    runCatching { mmr.release() }
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(mediaId, trackId) {
        val id = trackId ?: run {
            state = DispState.Editing
            return@LaunchedEffect
        }
        val c = LyricsCache.read(context, id)
        state = when {
            !c.synced.isNullOrBlank() -> {
                val lines = LrcParser.parse(c.synced!!)
                if (lines.isNotEmpty()) DispState.Synced(lines) else DispState.Editing
            }
            !c.plain.isNullOrBlank() -> DispState.Plain(c.plain!!)
            else -> DispState.Editing
        }
    }

    LaunchedEffect(mediaId, trigger) {
        if (trigger == 0) return@LaunchedEffect
        val id = trackId ?: run { state = DispState.NotFound; return@LaunchedEffect }
        state = DispState.Loading
        val r = LyricsFetcher().fetch(editTitle, editArtist, filename, (durationMs / 1000).toInt())
        state = when (r) {
            is LyricsFetchResult.Found -> {
                LyricsCache.writeLyrics(context, id, r.result.syncedLyrics, r.result.plainLyrics)
                when {
                    !r.result.syncedLyrics.isNullOrBlank() -> {
                        val lines = LrcParser.parse(r.result.syncedLyrics!!)
                        if (lines.isNotEmpty()) DispState.Synced(lines) else DispState.NotFound
                    }
                    !r.result.plainLyrics.isNullOrBlank() -> DispState.Plain(r.result.plainLyrics!!)
                    else -> DispState.NotFound
                }
            }
            LyricsFetchResult.NotFound -> DispState.NotFound
            is LyricsFetchResult.NetworkError -> DispState.Error(r.message)
            LyricsFetchResult.Timeout -> DispState.Error("timeout")
        }
    }

    LaunchedEffect(mediaId, transcribeTrigger) {
        if (transcribeTrigger == 0) return@LaunchedEffect
        val id = trackId ?: return@LaunchedEffect
        val uri = contentUri ?: run {
            state = DispState.Error("No audio URI")
            return@LaunchedEffect
        }
        val model = availableModels.firstOrNull { it.id == selectedModelId }
            ?: availableModels.firstOrNull()
            ?: run {
                state = DispState.Error("No ASR model downloaded")
                return@LaunchedEffect
            }
        state = DispState.Transcribing(0f, "Loading ${model.displayName}…")
        try {
            val text = AsrManager.transcribe(
                context = context,
                uri = uri,
                modelDir = model.dir,
                startMs = 0L,
                totalMs = 30_000L,
                chunkMs = 5_000L,
            ) { p ->
                state = DispState.Transcribing(p, "Transcribing audio…")
            }
            if (text.isBlank()) {
                state = DispState.NotFound
            } else {
                LyricsCache.writeLyrics(context, id, null, text)
                state = DispState.Plain(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = DispState.Error("Transcribe: ${e.message ?: "unknown"}")
        }
    }

    LaunchedEffect(mediaId, trackId, state, prefs.showTranslation, prefs.translationLang) {
        if (!prefs.showTranslation) { translations = null; return@LaunchedEffect }
        val synced = state as? DispState.Synced ?: return@LaunchedEffect
        val id = trackId ?: return@LaunchedEffect
        val lines = synced.lines.map { it.text }

        val cached = LyricsCache.readTranslation(context, id)
        if (cached != null && cached.first == prefs.translationLang) {
            val arr = cached.second.split("\n")
            if (arr.size == lines.size) { translations = arr; return@LaunchedEffect }
        }

        translating = true
        try {
            val src = TranslationManager.detect(lines.firstOrNull().orEmpty())
            if (src == "und" || src == prefs.translationLang) {
                translations = emptyList()
                return@LaunchedEffect
            }
            TranslationManager.ensureModel(src, prefs.translationLang)
            val out = ArrayList<String>(lines.size)
            for (line in lines) {
                if (line.isBlank()) { out += ""; continue }
                val tr = runCatching {
                    TranslationManager.translate(line, src, prefs.translationLang)
                }.getOrDefault("")
                out += tr
            }
            translations = out
            LyricsCache.writeTranslation(
                context, id, prefs.translationLang, out.joinToString("\n"),
            )
        } catch (_: Throwable) {
            translations = null
        } finally {
            translating = false
        }
    }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(150)
            positionMs = c.currentPosition
            playing = c.isPlaying
        }
    }

    Box(Modifier.fillMaxSize().background(fallbackBackground())) {
        albumArt?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .blur(90.dp)
                    .graphicsLayer(alpha = 0.55f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.35f),
                        Color.Black.copy(alpha = 0.65f),
                        Color.Black.copy(alpha = 0.9f),
                    )
                )
            )
        )

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = "Back", tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        editTitle.ifBlank { rawTitle.ifBlank { "—" } },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (editArtist.isNotBlank()) {
                        Text(
                            editArtist, color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (translating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 8.dp),
                        color = Color.White, strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = { state = DispState.Editing }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = "Edit tags", tint = Color.White,
                    )
                }
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "Settings", tint = Color.White,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    DispState.Editing -> EditForm(
                        editTitle = editTitle,
                        editArtist = editArtist,
                        filename = filename,
                        folder = folder,
                        availableModels = availableModels,
                        selectedModelId = selectedModelId,
                        onTitleChange = { editTitle = it },
                        onArtistChange = { editArtist = it },
                        onSearch = { trigger += 1 },
                        onTranscribe = { transcribeTrigger += 1 },
                        onModelSelect = { newId ->
                            selectedModelId = newId
                            scope.launch { AsrModels.saveSelected(context, newId) }
                        },
                    )
                    DispState.Loading -> LoadingPulse()
                    is DispState.Transcribing -> TranscribingBody(s.progress, s.status)
                    is DispState.Synced -> SyncedBody(
                        lines = s.lines,
                        translations = translations,
                        positionMs = positionMs,
                        prefs = prefs,
                        onSeek = { ms -> controller?.seekTo(ms) },
                    )
                    is DispState.Plain -> PlainBody(s.text)
                    DispState.NotFound -> NotFoundBody(
                        onEdit = { state = DispState.Editing },
                        onGoogle = {
                            val q = URLEncoder.encode(
                                "$editTitle $editArtist lyrics", "UTF-8"
                            )
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.google.com/search?q=$q"),
                                )
                            )
                        },
                    )
                    is DispState.Error -> ErrorBody(
                        msg = s.msg,
                        onRetry = { trigger += 1 },
                        onEdit = { state = DispState.Editing },
                    )
                }
            }

            if (controller != null) {
                GlassControls(controller, positionMs, durationMs, playing)
            }
        }
    }

    if (settingsOpen) {
        DisplaySettingsSheet(
            prefs = prefs,
            onChange = { p ->
                prefs = p
                scope.launch { LyricsDisplayPrefs.save(context, p) }
            },
            onDismiss = { settingsOpen = false },
        )
    }
}

private fun fallbackBackground(): Brush = Brush.verticalGradient(
    listOf(Color(0xFF0A0A12), Color(0xFF050508), Color.Black)
)

@Composable
private fun LoadingPulse() {
    val t = rememberInfiniteTransition(label = "loading")
    val scale by t.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val alpha by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "♪",
                fontSize = 84.sp,
                color = Color.White.copy(alpha = alpha),
                modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Searching lyrics…",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Trying multiple tag combinations",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TranscribingBody(progress: Float, status: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "🎤",
                fontSize = 72.sp,
                modifier = Modifier.graphicsLayer(
                    scaleX = 0.9f + progress * 0.2f,
                    scaleY = 0.9f + progress * 0.2f,
                ),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                status,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${(progress * 100).toInt()}%  •  30s sample",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "This may take 1–2 minutes on CPU.\nLeaving the screen will cancel.",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(
    editTitle: String,
    editArtist: String,
    filename: String,
    folder: String,
    availableModels: List<AsrModels.AvailableAsr>,
    selectedModelId: String,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTranscribe: () -> Unit,
    onModelSelect: (String) -> Unit,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color.White.copy(alpha = 0.65f),
        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
        cursorColor = Color.White,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Lyrics",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Fix tags if needed, then search online or transcribe offline.",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = editTitle,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = editArtist,
            onValueChange = onArtistChange,
            label = { Text("Artist (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )

        Spacer(Modifier.height(20.dp))
        if (filename.isNotBlank()) {
            FieldBlock("File", filename)
            Spacer(Modifier.height(10.dp))
        }
        if (folder.isNotBlank()) {
            FieldBlock("Folder", folder)
        }

        Spacer(Modifier.height(24.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.18f),
            modifier = Modifier.clickable(onClick = onSearch),
        ) {
            Text(
                "Search lyrics",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (availableModels.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            if (availableModels.size > 1) {
                Text(
                    "Model",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableModels.forEach { m ->
                        val isSel = m.id == selectedModelId
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isSel) Color.White.copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.clickable { onModelSelect(m.id) },
                        ) {
                            Column(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    m.displayName,
                                    color = if (isSel) Color.White
                                            else Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSel) FontWeight.Bold
                                                 else FontWeight.Normal,
                                )
                                Text(
                                    m.arch.uppercase(),
                                    color = Color.White.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.clickable(onClick = onTranscribe),
            ) {
                Text(
                    "🎤 Transcribe audio (offline)",
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Uses 30s sample · may take 1–2 min",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FieldBlock(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SyncedBody(
    lines: List<LrcLine>,
    translations: List<String>?,
    positionMs: Long,
    prefs: DisplayPrefs,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val rawActive = LrcParser.activeIndex(lines, positionMs)
    val active = if (rawActive < 0) 0 else rawActive

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vp = maxHeight * 0.5f
        LaunchedEffect(active, maxHeight) {
            try {
                listState.animateScrollToItem(active)
            } catch (_: Exception) {}
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = vp),
        ) {
            itemsIndexed(lines) { idx, line ->
                val dist = abs(idx - active)
                SyncedLine(
                    text = line.text,
                    translation = translations?.getOrNull(idx),
                    isActive = idx == active,
                    distance = dist,
                    prefs = prefs,
                    onClick = { onSeek(line.timeMs) },
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(70.dp).background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                )
            )
        )
        Box(
            Modifier.fillMaxWidth().height(110.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                )
            )
        )
    }
}

@Composable
private fun SyncedLine(
    text: String,
    translation: String?,
    isActive: Boolean,
    distance: Int,
    prefs: DisplayPrefs,
    onClick: () -> Unit,
) {
    val targetAlpha = when {
        isActive -> 1f
        distance == 1 -> 0.65f
        distance == 2 -> 0.35f
        distance == 3 -> 0.18f
        distance == 4 -> 0.06f
        else -> 0f
    }
    val targetBlur = when {
        isActive -> 0f
        distance == 1 -> 2f
        distance == 2 -> 5f
        distance == 3 -> 8f
        else -> 0f
    }
    val targetScale = when {
        isActive -> 1f
        distance == 1 -> 0.94f
        else -> 0.88f
    }
    val alpha by animateFloatAsState(targetAlpha, label = "lyricAlpha")
    val blurAmt by animateFloatAsState(targetBlur, label = "lyricBlur")
    val scale by animateFloatAsState(targetScale, label = "lyricScale")

    val fontSize = if (isActive) prefs.activeFontSize else prefs.fontSize
    val color = if (isActive) Color(prefs.colorActive) else Color.White

    val base = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 10.dp)
        .graphicsLayer(alpha = alpha, scaleX = scale, scaleY = scale)

    val withBlur = if (blurAmt >= 1f && alpha > 0.05f) base.blur(blurAmt.dp) else base

    Column(withBlur, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text.ifBlank { "♪" },
            color = color,
            fontSize = fontSize.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = (fontSize * 1.2f).sp,
        )
        if (prefs.showTranslation && !translation.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                translation,
                color = if (isActive)
                    Color.White.copy(alpha = 0.9f)
                else
                    Color.White.copy(alpha = 0.45f),
                fontSize = (fontSize * 0.58f).sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = (fontSize * 0.72f).sp,
            )
        }
    }
}

@Composable
private fun PlainBody(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 18.sp,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun NotFoundBody(onEdit: () -> Unit, onGoogle: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Lyrics not found.", color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(6.dp))
            Text(
                "Try editing tags or transcribing offline.",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChipButton("Edit tags", onEdit)
                ChipButton("Google", onGoogle)
            }
        }
    }
}

@Composable
private fun ErrorBody(msg: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Error: $msg", color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChipButton("Retry", onRetry)
                ChipButton("Edit tags", onEdit)
            }
        }
    }
}

@Composable
private fun ChipButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.12f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GlassControls(
    controller: MediaController,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(30.dp),
        color = Color.White.copy(alpha = 0.08f),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatMs(positionMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
                Slider(
                    value = if (durationMs > 0)
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = {
                        if (durationMs > 0) controller.seekTo((it * durationMs).toLong())
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
                Text(
                    formatMs(durationMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { controller.seekToPreviousMediaItem() },
                    enabled = controller.hasPreviousMediaItem(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_skip_previous),
                        "Previous",
                        tint = Color.White.copy(alpha =
                            if (controller.hasPreviousMediaItem()) 1f else 0.3f),
                        modifier = Modifier.size(34.dp),
                    )
                }
                Box(
                    Modifier.size(64.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable {
                            if (playing) controller.pause() else controller.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        ),
                        if (playing) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(
                    onClick = { controller.seekToNextMediaItem() },
                    enabled = controller.hasNextMediaItem(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_skip_next),
                        "Next",
                        tint = Color.White.copy(alpha =
                            if (controller.hasNextMediaItem()) 1f else 0.3f),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySettingsSheet(
    prefs: DisplayPrefs,
    onChange: (DisplayPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Subtitle size", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Inactive: ${prefs.fontSize}sp  •  Active: ${prefs.activeFontSize}sp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = prefs.fontSize.toFloat(),
                onValueChange = {
                    onChange(prefs.copy(fontSize = it.toInt().coerceIn(14, 40)))
                },
                valueRange = 14f..40f,
            )
            Slider(
                value = prefs.activeFontSize.toFloat(),
                onValueChange = {
                    onChange(prefs.copy(activeFontSize = it.toInt().coerceIn(20, 64)))
                },
                valueRange = 20f..64f,
            )
            Spacer(Modifier.height(16.dp))
            Text("Active color", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    0xFFE91E63L, 0xFFFF5722L, 0xFF2196F3L,
                    0xFF00BCD4L, 0xFF4CAF50L, 0xFFFFC107L, 0xFFFFFFFFL,
                ).forEach { argb ->
                    Box(
                        Modifier.size(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(argb))
                            .clickable { onChange(prefs.copy(colorActive = argb)) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show translation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Under each line via ML Kit (~30MB one-time download)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.showTranslation,
                    onCheckedChange = { onChange(prefs.copy(showTranslation = it)) },
                )
            }
            if (prefs.showTranslation) {
                Spacer(Modifier.height(8.dp))
                Text("Target language", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "fa" to "Persian", "en" to "English",
                        "ar" to "Arabic", "tr" to "Turkish",
                    ).forEach { (code, label) ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (prefs.translationLang == code)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable {
                                onChange(prefs.copy(translationLang = code))
                            },
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(
                                    horizontal = 14.dp, vertical = 6.dp,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
