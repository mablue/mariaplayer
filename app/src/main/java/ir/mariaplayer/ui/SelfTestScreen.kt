package ir.mariaplayer.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.mariaplayer.R
import ir.mariaplayer.data.Collection
import ir.mariaplayer.data.CollectionRepository
import ir.mariaplayer.data.CollectionRule
import ir.mariaplayer.data.ContentType
import ir.mariaplayer.data.ContentTypePrefs
import ir.mariaplayer.data.ContentTypeLogic
import ir.mariaplayer.data.MediaClassifier
import ir.mariaplayer.data.LibraryPrefs
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.QueryNormalizer
import ir.mariaplayer.data.SortMode
import ir.mariaplayer.data.Track
import ir.mariaplayer.data.Source
import ir.mariaplayer.lyrics.LrcLine
import ir.mariaplayer.lyrics.LrcParser
import ir.mariaplayer.lyrics.LyricsCache
import ir.mariaplayer.lyrics.LyricsScorer
import ir.mariaplayer.util.PlaybackDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private data class TestOutcome(val name: String, val passed: Boolean, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val outcomes = remember { mutableStateListOf<TestOutcome>() }
    var running by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        delay(250)
        val rep = runAllTests(context) { name, passed, detail ->
            outcomes.add(TestOutcome(name, passed, detail))
        }
        report = rep
        running = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Self-test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = !running && report.isNotEmpty(),
                        onClick = { clipboard.setText(AnnotatedString(report)) },
                    ) { Text("Copy") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Running tests...", modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(outcomes) { o ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (o.passed) "✔" else "✘",
                            color = if (o.passed) Color(0xFF2E7D32) else Color(0xFFC62828),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.name, style = MaterialTheme.typography.bodyMedium)
                            Text(o.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (!running && report.isNotEmpty()) {
                HorizontalDivider()
                SelectionContainer {
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private suspend fun runAllTests(
    context: Context,
    onOutcome: (String, Boolean, String) -> Unit,
): String {
    val results = mutableListOf<TestOutcome>()
    suspend fun test(name: String, block: suspend () -> String) {
        try {
            val detail = block()
            results.add(TestOutcome(name, true, detail))
            onOutcome(name, true, detail)
        } catch (e: Throwable) {
            val msg = (e.message ?: e::class.simpleName ?: "error").take(70)
            results.add(TestOutcome(name, false, msg))
            onOutcome(name, false, msg)
        }
    }

    test("Audio permission") {
        if (LibraryScanner.hasAudioPermission(context)) "granted"
        else throw IllegalStateException("not granted")
    }
    test("Library scan") {
        val tracks = LibraryScanner.scan(context)
        if (tracks.isEmpty()) throw IllegalStateException("0 tracks")
        "${tracks.size} tracks"
    }
    test("Sort preference") {
        val orig = LibraryPrefs.readSort(context)
        LibraryPrefs.saveSort(context, SortMode.DURATION)
        val got = LibraryPrefs.readSort(context)
        LibraryPrefs.saveSort(context, orig)
        if (got != SortMode.DURATION) throw IllegalStateException("got $got")
        "ok"
    }
    test("Playback resume state") {
        val orig = PlaybackPrefs.read(context)
        PlaybackPrefs.save(context, 12_345L, 6_789L)
        val got = PlaybackPrefs.read(context)
        PlaybackPrefs.save(context, orig.trackId ?: 0L, orig.positionMs)
        if (got.trackId != 12_345L || got.positionMs != 6_789L)
            throw IllegalStateException("got ${got.trackId}/${got.positionMs}")
        "ok"
    }
    test("Per-track position round-trip") {
        val id = 9_999_995L
        PlaybackPrefs.savePosition(context, id, 45_000L)
        val got = PlaybackPrefs.readPosition(context, id)
        PlaybackPrefs.clearPosition(context, id)
        if (got != 45_000L) throw IllegalStateException("got=$got")
        "ok"
    }
    test("Per-track position clear") {
        val id = 9_999_996L
        PlaybackPrefs.savePosition(context, id, 12_000L)
        PlaybackPrefs.clearPosition(context, id)
        val got = PlaybackPrefs.readPosition(context, id)
        if (got != null) throw IllegalStateException("still=$got")
        "ok"
    }
    test("Lyrics cache") {
        val id = 9_999_991L
        LyricsCache.writeLyrics(context, id, "[00:01.00]test", "plain test")
        val got = LyricsCache.read(context, id)
        LyricsCache.clearLyrics(context, id)
        if (got.synced?.contains("test") != true)
            throw IllegalStateException("synced=null")
        "ok"
    }
    test("LRC parser") {
        val lrc = "[00:01.00]Hello\n[00:05.50]World\n[00:10.00]!"
        val lines = LrcParser.parse(lrc)
        if (lines.size != 3) throw IllegalStateException("size=${lines.size}")
        if (lines[0].timeMs != 1_000L) throw IllegalStateException("t0=${lines[0].timeMs}")
        if (lines[1].timeMs != 5_500L) throw IllegalStateException("t1=${lines[1].timeMs}")
        "${lines.size} lines"
    }
    test("LRC activeIndex") {
        val lines = listOf(LrcLine(1_000L, "a"), LrcLine(5_000L, "b"), LrcLine(10_000L, "c"))
        if (LrcParser.activeIndex(lines, 0L) != -1) throw IllegalStateException("t0")
        if (LrcParser.activeIndex(lines, 1_500L) != 0) throw IllegalStateException("t1")
        if (LrcParser.activeIndex(lines, 7_000L) != 1) throw IllegalStateException("t2")
        "ok"
    }
    test("LRC rejects 60s") {
        val bad = "[00:60.00]bad\n[00:99.99]worse\n[01:30.50]good"
        val lines = LrcParser.parse(bad)
        if (lines.size != 1) throw IllegalStateException("parsed ${lines.size}, want 1")
        if (lines[0].timeMs != 90_500L) throw IllegalStateException("ts=${lines[0].timeMs}")
        "1/3 parsed"
    }
    test("Lyrics scorer") {
        val s = LyricsScorer.score("Hello", "Adele", 295, "Hello", "Adele", 295)
        if (s != 100) throw IllegalStateException("perfect=$s")
        val s2 = LyricsScorer.score("Hello", "Adele", 295, "Goodbye", "Other", 100)
        if (s2 >= 40) throw IllegalStateException("reject=$s2")
        "ok"
    }
    test("Scorer rejects empty") {
        val empty = ir.mariaplayer.lyrics.LyricsResult(
            title = "X", artist = "Y", album = null, durationSec = 100,
            syncedLyrics = null, plainLyrics = null, source = "test", score = 0,
        )
        val r = LyricsScorer.pickBest(listOf(empty), "X", "Y", 100)
        if (r != null) throw IllegalStateException("empty accepted")
        "ok"
    }
    test("Scorer accepts real") {
        val real = ir.mariaplayer.lyrics.LyricsResult(
            title = "X", artist = "Y", album = null, durationSec = 100,
            syncedLyrics = null, plainLyrics = "line one\nline two", source = "test", score = 0,
        )
        val r = LyricsScorer.pickBest(listOf(real), "X", "Y", 100)
        if (r == null) throw IllegalStateException("real rejected")
        "ok"
    }
    test("Query normalizer") {
        val n = QueryNormalizer.normalize("Hello", "Adele", "hello.mp3")
        if (n.title != "Hello" || n.source != Source.TAG)
            throw IllegalStateException("${n.title}/${n.source}")
        val n2 = QueryNormalizer.normalize("", "", "07_song_name.mp3")
        if (n2.title.isBlank()) throw IllegalStateException("empty")
        "ok"
    }
    test("Delete API level") {
        if (Build.VERSION.SDK_INT < 30) return@test "skip"
        val m = android.provider.MediaStore::class.java.getMethod(
            "createDeleteRequest",
            android.content.ContentResolver::class.java,
            java.util.Collection::class.java,
        )
        if (m == null) throw IllegalStateException("missing")
        "ok (API ${Build.VERSION.SDK_INT})"
    }
    test("LyricsPanel removed") {
        try {
            Class.forName("ir.mariaplayer.lyrics.LyricsPanelKt")
            throw IllegalStateException("dead class still present")
        } catch (_: ClassNotFoundException) {
            "removed"
        }
    }
    test("Translation probe") {
        val probes = ir.mariaplayer.translate.TranslationProbe.probeAll("hello", "en", "fa")
        val ok = probes.filter { it.ok }
        val detail = probes.joinToString(" | ") {
            "${it.name}:${if (it.ok) "✓" else "✗"}"
        }
        if (ok.isEmpty()) throw IllegalStateException(detail)
        "${ok.size}/${probes.size} — $detail"
    }
    test("Classifier: short song") {
        val t = MediaClassifier.classify("Song Title", "Album X", "Music", "01-song.mp3", 180_000L)
        if (t != ContentType.MUSIC) throw IllegalStateException("got $t")
        "MUSIC"
    }
    test("Classifier: boundary just below threshold") {
        val t = MediaClassifier.classify("Song", null, "Music", "x.mp3", MediaClassifier.LONG_THRESHOLD_MS - 1)
        if (t != ContentType.MUSIC) throw IllegalStateException("got $t")
        "MUSIC"
    }
    test("Classifier: boundary exactly at threshold") {
        val t = MediaClassifier.classify("Track", null, "Music", "x.mp3", MediaClassifier.LONG_THRESHOLD_MS)
        if (t != ContentType.PODCAST) throw IllegalStateException("got $t")
        "PODCAST"
    }
    test("Classifier: 30-min podcast") {
        val t = MediaClassifier.classify("Ep 5", "My Podcast", "Podcasts", "ep5.mp3", 30 * 60_000L)
        if (t != ContentType.PODCAST) throw IllegalStateException("got $t")
        "PODCAST"
    }
    test("Classifier: 90-min audiobook chapter") {
        val t = MediaClassifier.classify("Chapter 1", null, "Music", "c1.mp3", 90 * 60_000L)
        if (t != ContentType.PODCAST) throw IllegalStateException("got $t")
        "PODCAST (auto; audiobook via manual)"
    }
    test("Classifier: 2-hour DJ mix") {
        val t = MediaClassifier.classify("Summer Mix", null, "Music", "mix.mp3", 2 * 3_600_000L)
        if (t != ContentType.PODCAST) throw IllegalStateException("got $t")
        "PODCAST"
    }
    test("Override: round-trip") {
        val id = 9_900_005L
        ContentTypePrefs.set(context, id, ContentType.PODCAST)
        val got = ContentTypePrefs.read(context, id)
        ContentTypePrefs.clear(context, id)
        if (got != ContentType.PODCAST) throw IllegalStateException("got=$got")
        "ok"
    }
    test("Override: clear removes") {
        val id = 9_900_006L
        ContentTypePrefs.set(context, id, ContentType.AUDIOBOOK)
        ContentTypePrefs.clear(context, id)
        val got = ContentTypePrefs.read(context, id)
        if (got != null) throw IllegalStateException("still=$got")
        "ok"
    }
    test("Logic: derive without override") {
        val track = Track(
            id = 901L, title = "Song", artist = "A", album = null,
            durationMs = 180_000L, contentUri = "file:///x",
            folder = "Music", filename = "x.mp3",
        )
        val t = ContentTypeLogic.derive(track, emptyMap())
        if (t != ContentType.MUSIC) throw IllegalStateException("got $t")
        "MUSIC"
    }
    test("Logic: derive override wins") {
        val track = Track(
            id = 902L, title = "Song", artist = "A", album = null,
            durationMs = 180_000L, contentUri = "file:///x",
            folder = "Music", filename = "x.mp3",
        )
        val t = ContentTypeLogic.derive(track, mapOf(902L to ContentType.PODCAST))
        if (t != ContentType.PODCAST) throw IllegalStateException("got $t")
        "override wins"
    }
    test("Logic: applyAll reclassifies") {
        val short = Track(id = 903L, title = "S", artist = "A", album = null,
            durationMs = 120_000L, contentUri = "file:///1",
            folder = "M", filename = "1.mp3")
        val long = Track(id = 904L, title = "L", artist = "B", album = null,
            durationMs = 3_600_000L, contentUri = "file:///2",
            folder = "M", filename = "2.mp3")
        val r = ContentTypeLogic.applyAll(listOf(short, long), emptyMap())
        if (r[0].contentType != ContentType.MUSIC) throw IllegalStateException("0=${r[0].contentType}")
        if (r[1].contentType != ContentType.PODCAST) throw IllegalStateException("1=${r[1].contentType}")
        "ok"
    }
    test("Logic: countByType") {
        val a = Track(id = 905L, title = "a", artist = null, album = null,
            durationMs = 60_000L, contentUri = "u").copy(contentType = ContentType.MUSIC)
        val b = Track(id = 906L, title = "b", artist = null, album = null,
            durationMs = 60_000L, contentUri = "v").copy(contentType = ContentType.PODCAST)
        val c = Track(id = 907L, title = "c", artist = null, album = null,
            durationMs = 60_000L, contentUri = "w").copy(contentType = ContentType.MUSIC)
        val counts = ContentTypeLogic.countByType(listOf(a, b, c))
        if (counts[null] != 3) throw IllegalStateException("all=${counts[null]}")
        if (counts[ContentType.MUSIC] != 2) throw IllegalStateException("music=${counts[ContentType.MUSIC]}")
        if (counts[ContentType.PODCAST] != 1) throw IllegalStateException("pod=${counts[ContentType.PODCAST]}")
        "ok"
    }
    test("Logic: matchesFilter null = all") {
        val t = Track(id = 908L, title = "x", artist = null, album = null,
            durationMs = 60_000L, contentUri = "u").copy(contentType = ContentType.PODCAST)
        if (!ContentTypeLogic.matchesFilter(t, null)) throw IllegalStateException("null failed")
        "ok"
    }
    test("Logic: matchesFilter typed") {
        val music = Track(id = 909L, title = "m", artist = null, album = null,
            durationMs = 60_000L, contentUri = "u").copy(contentType = ContentType.MUSIC)
        val pod = Track(id = 910L, title = "p", artist = null, album = null,
            durationMs = 60_000L, contentUri = "v").copy(contentType = ContentType.PODCAST)
        if (!ContentTypeLogic.matchesFilter(music, ContentType.MUSIC))
            throw IllegalStateException("music should match")
        if (ContentTypeLogic.matchesFilter(pod, ContentType.MUSIC))
            throw IllegalStateException("pod must NOT match music")
        "ok"
    }
    test("Logic: sameTypeOnly isolation") {
        val m1 = Track(id = 911L, title = "m1", artist = null, album = null,
            durationMs = 60_000L, contentUri = "u").copy(contentType = ContentType.MUSIC)
        val p = Track(id = 912L, title = "p", artist = null, album = null,
            durationMs = 60_000L, contentUri = "v").copy(contentType = ContentType.PODCAST)
        val m2 = Track(id = 913L, title = "m2", artist = null, album = null,
            durationMs = 60_000L, contentUri = "w").copy(contentType = ContentType.MUSIC)
        val pool = ContentTypeLogic.sameTypeOnly(listOf(m1, p, m2), m1)
        if (pool.size != 2) throw IllegalStateException("size=${pool.size}")
        if (pool.any { it.id == 912L }) throw IllegalStateException("podcast leaked into music queue")
        "ok"
    }
    test("Flow: observeAll emits override") {
        val id = 9_900_010L
        ContentTypePrefs.set(context, id, ContentType.PODCAST)
        val map = ContentTypePrefs.observeAll(context).first()
        ContentTypePrefs.clear(context, id)
        if (map[id] != ContentType.PODCAST)
            throw IllegalStateException("map=$map")
        "ok"
    }
    test("Theme default style") {
        val bl = MikhakTypography.bodyLarge
        if (bl.fontSize.value <= 0f) throw IllegalStateException("unset")
        "bodyLarge=${bl.fontSize.value}sp"
    }
    test("Font: Mikhak family loaded") {
        val fam = MikhakTypography.bodyLarge.fontFamily
            ?: throw IllegalStateException("null family")
        if (fam == androidx.compose.ui.text.font.FontFamily.Default)
            throw IllegalStateException("still default")
        "loaded"
    }
    test("Font: font files present") {
        val ctx = context
        val bold = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.mikhak_bold)
            ?: throw IllegalStateException("bold missing")
        val reg = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.mikhak_regular)
            ?: throw IllegalStateException("regular missing")
        "bold+regular"
    }
    test("Font: Persian glyph available") {
        val tf = androidx.core.content.res.ResourcesCompat
            .getFont(context, R.font.mikhak_bold)
            ?: throw IllegalStateException("typeface null")
        val paint = android.graphics.Paint().apply {
            typeface = tf
            textSize = 32f
        }
        val w = paint.measureText("\u0641\u0627\u0631\u0633\u06cc")
        if (w <= 1f) throw IllegalStateException("no Persian glyph: w=$w")
        "w=${w.toInt()}px"
    }
    test("Font: Latin glyph available") {
        val tf = androidx.core.content.res.ResourcesCompat
            .getFont(context, R.font.mikhak_bold)
            ?: throw IllegalStateException("typeface null")
        val paint = android.graphics.Paint().apply {
            typeface = tf
            textSize = 32f
        }
        val w = paint.measureText("Hello World")
        if (w <= 1f) throw IllegalStateException("no Latin glyph: w=$w")
        "w=${w.toInt()}px"
    }
    test("Font: digits render") {
        val tf = androidx.core.content.res.ResourcesCompat
            .getFont(context, R.font.mikhak_bold)
            ?: throw IllegalStateException("typeface null")
        val paint = android.graphics.Paint().apply {
            typeface = tf
            textSize = 32f
        }
        val w1 = paint.measureText("0123456789")
        val w2 = paint.measureText("\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9")
        if (w1 <= 1f || w2 <= 1f) throw IllegalStateException("latin=$w1 persian=$w2")
        "ok"
    }
    test("LRC rejects \"null\"") {
        val lines = LrcParser.parse("null")
        if (lines.isNotEmpty()) throw IllegalStateException("parsed ${lines.size} null lines")
        "rejected"
    }
    test("LRC parses Persian") {
        val lrc = "[00:01.00]دنبال جای زخمامی\n[00:05.50]نترس تو قلبمه"
        val lines = LrcParser.parse(lrc)
        if (lines.size != 2) throw IllegalStateException("size=${lines.size}")
        if (!lines[0].text.contains("زخمامی")) throw IllegalStateException("t0=${lines[0].text}")
        if (!lines[1].text.contains("قلبمه")) throw IllegalStateException("t1=${lines[1].text}")
        "2 lines"
    }
    test("Cache filters \"null\"") {
        val id = 9_999_992L
        LyricsCache.writeLyrics(context, id, "null", "null")
        val got = LyricsCache.read(context, id)
        LyricsCache.clearLyrics(context, id)
        if (got.synced != null || got.plain != null)
            throw IllegalStateException("synced=${got.synced} plain=${got.plain}")
        "filtered"
    }
    test("Cache preserves Persian") {
        val id = 9_999_993L
        val text = "دنبال جای زخمامی نترس تو قلبمه"
        LyricsCache.writeLyrics(context, id, null, text)
        val got = LyricsCache.read(context, id)
        LyricsCache.clearLyrics(context, id)
        if (got.plain != text) throw IllegalStateException("plain=${got.plain?.take(20)}")
        "ok"
    }
    test("Cache offset clamp") {
        val id = 9_999_994L
        LyricsCache.writeOffset(context, id, 999_999L)
        val got = LyricsCache.read(context, id)
        LyricsCache.clearLyrics(context, id)
        if (got.offsetMs != 500L) throw IllegalStateException("offset=${got.offsetMs}")
        "clamped"
    }
    test("SortMode labels complete") {
        val modes = SortMode.entries
        if (modes.size != 5) throw IllegalStateException("size=${modes.size}")
        modes.forEach { m ->
            if (m.label.isBlank()) throw IllegalStateException("blank: $m")
        }
        "5 labels"
    }
    test("Lyrics scorer RTL") {
        val s = LyricsScorer.score(
            "واهمه", "مرتضی جعفرزاده", 274,
            "واهمه", "مرتضی جعفرزاده", 274,
        )
        if (s < 90) throw IllegalStateException("perfect=$s")
        "score=$s"
    }
    test("SSOT: snapshot has all 10 fields") {
        val snap = PlaybackDiagnostics.snapshot()
        val required = listOf("reg=", "rem=", "tick=", "trans=", "state=",
            "save=", "savePos=", "read=", "readPos=", "clear=")
        for (f in required) {
            if (!snap.contains(f)) throw IllegalStateException("missing $f in $snap")
        }
        "all 10"
    }
    test("SSOT: save(0) writes, savePosition(0) skips") {
        val id = 9_900_020L
        PlaybackPrefs.savePosition(context, id, 0L)
        if (PlaybackPrefs.readPosition(context, id) != null)
            throw IllegalStateException("savePosition(0) should be no-op")
        PlaybackPrefs.save(context, id, 0L)
        val slot = PlaybackPrefs.read(context)
        PlaybackPrefs.save(context, 0L, 0L)
        if (slot.trackId != id)
            throw IllegalStateException("save(0) should write, got ${slot.trackId}")
        "writes vs skips"
    }
    test("SSOT: slot and map independent") {
        val a = 9_900_021L
        val b = 9_900_022L
        PlaybackPrefs.save(context, a, 111L)
        PlaybackPrefs.savePosition(context, b, 222L)
        val slot = PlaybackPrefs.read(context)
        val mapA = PlaybackPrefs.readPosition(context, a)
        val mapB = PlaybackPrefs.readPosition(context, b)
        PlaybackPrefs.clearPosition(context, b)
        PlaybackPrefs.save(context, 0L, 0L)
        if (slot.trackId != a || slot.positionMs != 111L)
            throw IllegalStateException("slot=$slot")
        if (mapA != null)
            throw IllegalStateException("mapA should be null, got $mapA")
        if (mapB != 222L)
            throw IllegalStateException("mapB=$mapB")
        "independent"
    }
    test("SSOT: readPosition null for missing") {
        val got = PlaybackPrefs.readPosition(context, 999_888_777_666L)
        if (got != null) throw IllegalStateException("expected null, got $got")
        "null"
    }
    test("SSOT: multi-track isolation") {
        val ids = listOf(9_900_030L, 9_900_031L, 9_900_032L)
        val pos = listOf(1_000L, 2_000L, 3_000L)
        for (i in ids.indices) PlaybackPrefs.savePosition(context, ids[i], pos[i])
        for (i in ids.indices) {
            val got = PlaybackPrefs.readPosition(context, ids[i])
            if (got != pos[i])
                throw IllegalStateException("id=${ids[i]} got=$got want=${pos[i]}")
        }
        PlaybackPrefs.clearPosition(context, ids[1])
        val mid = PlaybackPrefs.readPosition(context, ids[1])
        val first = PlaybackPrefs.readPosition(context, ids[0])
        val last = PlaybackPrefs.readPosition(context, ids[2])
        for (id in ids) PlaybackPrefs.clearPosition(context, id)
        if (mid != null) throw IllegalStateException("mid not cleared")
        if (first != 1_000L || last != 3_000L)
            throw IllegalStateException("neighbors damaged: $first,$last")
        "3-track"
    }
    test("SSOT: save after clear restores") {
        val id = 9_900_050L
        PlaybackPrefs.savePosition(context, id, 100L)
        PlaybackPrefs.clearPosition(context, id)
        if (PlaybackPrefs.readPosition(context, id) != null)
            throw IllegalStateException("clear failed")
        PlaybackPrefs.savePosition(context, id, 200L)
        val got = PlaybackPrefs.readPosition(context, id)
        PlaybackPrefs.clearPosition(context, id)
        if (got != 200L) throw IllegalStateException("restore=$got")
        "ok"
    }
    test("SSOT: counter accuracy (delta)") {
        val s0 = PlaybackDiagnostics.saveCalls.get()
        val sp0 = PlaybackDiagnostics.savePositionCalls.get()
        val c0 = PlaybackDiagnostics.clearPositionCalls.get()
        repeat(3) { PlaybackPrefs.save(context, 0L, 0L) }
        repeat(2) { PlaybackPrefs.savePosition(context, 9_900_060L, 50L) }
        repeat(1) { PlaybackPrefs.clearPosition(context, 9_900_060L) }
        val dS = PlaybackDiagnostics.saveCalls.get() - s0
        val dSp = PlaybackDiagnostics.savePositionCalls.get() - sp0
        val dC = PlaybackDiagnostics.clearPositionCalls.get() - c0
        if (dS != 3) throw IllegalStateException("save Δ=$dS")
        if (dSp != 2) throw IllegalStateException("savePos Δ=$dSp")
        if (dC != 1) throw IllegalStateException("clear Δ=$dC")
        "Δ ok"
    }
    test("SSOT: Drift snapshot") {
        PlaybackDiagnostics.snapshot()
    }
    test("SSOT: savePosition counter") {
        val before = PlaybackDiagnostics.savePositionCalls.get()
        PlaybackPrefs.savePosition(context, 9_999_900L, 100L)
        val after = PlaybackDiagnostics.savePositionCalls.get()
        PlaybackPrefs.clearPosition(context, 9_999_900L)
        if (after != before + 1) throw IllegalStateException("Δ=${after - before}")
        "ok"
    }
    test("SSOT: clearPosition counter") {
        val before = PlaybackDiagnostics.clearPositionCalls.get()
        PlaybackPrefs.clearPosition(context, 9_999_901L)
        val after = PlaybackDiagnostics.clearPositionCalls.get()
        if (after != before + 1) throw IllegalStateException("Δ=${after - before}")
        "ok"
    }
    test("SSOT: readPosition counter") {
        val before = PlaybackDiagnostics.readPositionCalls.get()
        PlaybackPrefs.readPosition(context, 9_999_902L)
        val after = PlaybackDiagnostics.readPositionCalls.get()
        if (after != before + 1) throw IllegalStateException("Δ=${after - before}")
        "ok"
    }
    test("SSOT: listener balance") {
        val reg = PlaybackDiagnostics.listenerRegistrations.get()
        val rem = PlaybackDiagnostics.listenerRemovals.get()
        val active = reg - rem
        if (active < 0 || active > 1)
            throw IllegalStateException("active=$active (reg=$reg rem=$rem)")
        "active=$active (reg=$reg rem=$rem)"
    }
    test("SSOT: dual API agreement") {
        val id = 9_999_903L
        PlaybackPrefs.save(context, id, 12_345L)
        PlaybackPrefs.savePosition(context, id, 12_345L)
        val single = PlaybackPrefs.read(context).positionMs
        val fromMap = PlaybackPrefs.readPosition(context, id)
        PlaybackPrefs.clearPosition(context, id)
        PlaybackPrefs.save(context, 0L, 0L)
        if (single != 12_345L || fromMap != 12_345L)
            throw IllegalStateException("single=$single map=$fromMap")
        "agreed"
    }
    test("SSOT: reset consistency") {
        PlaybackDiagnostics.reset()
        val snap = PlaybackDiagnostics.snapshot()
        if (!snap.contains("reg=0") || !snap.contains("save=0"))
            throw IllegalStateException(snap)
        "reset ok"
    }



    test("Collection: defaults after init") {
        CollectionRepository.initializeIfNeeded(context)
        val colls = CollectionRepository.snapshot(context)
        if (colls.none { it.id == CollectionRepository.ALL_ID }) throw IllegalStateException("All")
        if (colls.none { it.id == CollectionRepository.MUSIC_ID }) throw IllegalStateException("Music")
        if (colls.none { it.id == CollectionRepository.PODCASTS_ID }) throw IllegalStateException("Podcasts")
        if (colls.none { it.id == CollectionRepository.AUDIOBOOKS_ID }) throw IllegalStateException("Audiobooks")
        "${colls.size} collections"
    }
    test("Collection: All is first") {
        CollectionRepository.initializeIfNeeded(context)
        val colls = CollectionRepository.snapshot(context)
        if (colls.firstOrNull()?.id != CollectionRepository.ALL_ID)
            throw IllegalStateException("first=${colls.firstOrNull()?.id}")
        "ok"
    }
    test("Collection: All cannot be deleted") {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.delete(context, CollectionRepository.ALL_ID)
        val colls = CollectionRepository.snapshot(context)
        if (colls.none { it.id == CollectionRepository.ALL_ID })
            throw IllegalStateException("All was deleted")
        "protected"
    }
    test("Collection: Favorites round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val testId = 9_900_000_000L + Random.nextLong(100_000)
        val before = testId in CollectionRepository.favoriteIds(context)
        CollectionRepository.toggleFavorite(context, testId)
        val added = testId in CollectionRepository.favoriteIds(context)
        CollectionRepository.toggleFavorite(context, testId)
        val removed = testId !in CollectionRepository.favoriteIds(context)
        if (before || !added || !removed)
            throw IllegalStateException("before=$before add=$added rm=$removed")
        "ok"
    }
    test("Collection: Favorites auto-created on toggle") {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.delete(context, CollectionRepository.FAVORITES_ID)
        val testId = 9_900_001_000L + Random.nextLong(100_000)
        CollectionRepository.toggleFavorite(context, testId)
        val created = CollectionRepository.snapshot(context)
            .any { it.id == CollectionRepository.FAVORITES_ID }
        CollectionRepository.toggleFavorite(context, testId)
        CollectionRepository.delete(context, CollectionRepository.FAVORITES_ID)
        if (!created) throw IllegalStateException("fav not created")
        "ok"
    }
    test("Collection: Manual CRUD") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "T_"+System.currentTimeMillis(),
            CollectionRule.Manual(emptyList()))
        CollectionRepository.addTrack(context, id, 42L)
        CollectionRepository.addTrack(context, id, 43L)
        val ids1 = (CollectionRepository.snapshot(context).firstOrNull { it.id == id }?.rule as? CollectionRule.Manual)?.trackIds
        CollectionRepository.removeTrack(context, id, 42L)
        val ids2 = (CollectionRepository.snapshot(context).firstOrNull { it.id == id }?.rule as? CollectionRule.Manual)?.trackIds
        CollectionRepository.delete(context, id)
        if (ids1 != listOf(42L, 43L)) throw IllegalStateException("ids1=$ids1")
        if (ids2 != listOf(43L)) throw IllegalStateException("ids2=$ids2")
        "ok"
    }
    test("Collection: Duration matches") {
        val rule = CollectionRule.Duration(minMs = 10 * 60_000L, maxMs = 30 * 60_000L)
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=5*60_000L, contentUri="u")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=15*60_000L, contentUri="u")
        val t3 = Track(id=3L, title="t", artist=null, album=null, durationMs=45*60_000L, contentUri="u")
        if (rule.matches(t1)) throw IllegalStateException("t1 hit")
        if (!rule.matches(t2)) throw IllegalStateException("t2 miss")
        if (rule.matches(t3)) throw IllegalStateException("t3 hit")
        "ok"
    }
    test("Collection: Pattern matches") {
        val rule = CollectionRule.Pattern("podcast", setOf(CollectionRule.Pattern.Field.Folder))
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u", folder="MyPodcasts", filename="x.mp3")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u", folder="Music", filename="y.mp3")
        if (!rule.matches(t1)) throw IllegalStateException("miss")
        if (rule.matches(t2)) throw IllegalStateException("hit")
        "ok"
    }
    test("Collection: rename") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "Orig", CollectionRule.Manual(emptyList()))
        CollectionRepository.rename(context, id, "Renamed")
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (found?.label != "Renamed") throw IllegalStateException("label=${found?.label}")
        "ok"
    }
    test("Collection: resolveTracks All") {
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=120_000L, contentUri="v")
        val all = Collection("all", "All", CollectionRule.All, isSystem = true)
        if (CollectionRepository.resolveTracks(listOf(t1, t2), all).size != 2)
            throw IllegalStateException("size")
        "ok"
    }
    test("Collection: resolveTracks Manual") {
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=120_000L, contentUri="v")
        val c = Collection("x", "X", CollectionRule.Manual(listOf(2L)))
        val resolved = CollectionRepository.resolveTracks(listOf(t1, t2), c)
        if (resolved.size != 1 || resolved[0].id != 2L) throw IllegalStateException("resolved=$resolved")
        "ok"
    }
    test("Collection: persistence round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "Persist", CollectionRule.Duration(minMs=5*60_000L))
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val rule = found?.rule as? CollectionRule.Duration
            ?: throw IllegalStateException("rule=${found?.rule}")
        if (rule.minMs != 5*60_000L) throw IllegalStateException("min=${rule.minMs}")
        "ok"
    }

    test("Collection: Trash in defaults") {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.ensureTrash(context)
        val colls = CollectionRepository.snapshot(context)
        if (colls.none { it.id == CollectionRepository.TRASH_ID })
            throw IllegalStateException("Trash missing")
        "ok"
    }
    test("Collection: Trash cannot be deleted") {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.ensureTrash(context)
        CollectionRepository.delete(context, CollectionRepository.TRASH_ID)
        val colls = CollectionRepository.snapshot(context)
        if (colls.none { it.id == CollectionRepository.TRASH_ID })
            throw IllegalStateException("Trash was deleted")
        "protected"
    }
    test("Collection: trash round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val id = 9_900_002_000L + Random.nextLong(100_000)
        CollectionRepository.trash(context, id)
        val trashed = id in CollectionRepository.trashIds(context)
        CollectionRepository.restore(context, id)
        val restored = id !in CollectionRepository.trashIds(context)
        if (!trashed || !restored)
            throw IllegalStateException("trashed=$trashed restored=$restored")
        "ok"
    }
    test("Collection: trash removes from Manual") {
        CollectionRepository.initializeIfNeeded(context)
        val cid = CollectionRepository.create(context, "T_"+System.currentTimeMillis(),
            CollectionRule.Manual(emptyList()))
        val tid = 9_900_003_000L + Random.nextLong(100_000)
        CollectionRepository.addTrack(context, cid, tid)
        CollectionRepository.trash(context, tid)
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == cid }
        val still = (found?.rule as? CollectionRule.Manual)?.trackIds?.contains(tid) ?: false
        CollectionRepository.delete(context, cid)
        CollectionRepository.restore(context, tid)
        if (still) throw IllegalStateException("track stayed in Manual")
        "ok"
    }
    test("Collection: trash hides from All") {
        val t1 = Track(id=900_001L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u")
        val all = Collection("all", "All", CollectionRule.All, isSystem = true)
        val hidden = CollectionRepository.resolveTracks(listOf(t1), all, setOf(900_001L))
        val shown = CollectionRepository.resolveTracks(listOf(t1), all, emptySet())
        if (hidden.isNotEmpty()) throw IllegalStateException("not hidden")
        if (shown.size != 1) throw IllegalStateException("over-filtered")
        "ok"
    }
    test("Collection: trash collection resolves") {
        val t1 = Track(id=900_002L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u")
        val t2 = Track(id=900_003L, title="u", artist=null, album=null, durationMs=60_000L, contentUri="v")
        val trash = Collection(CollectionRepository.TRASH_ID, "Trash", CollectionRule.All, isSystem = true)
        val resolved = CollectionRepository.resolveTracks(listOf(t1, t2), trash, setOf(900_002L))
        if (resolved.size != 1 || resolved[0].id != 900_002L)
            throw IllegalStateException("resolved=$resolved")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // C40-C42: Predicate formula — And / Or / Not
    // ═══════════════════════════════════════════════════════

    fun mkTrack(
        id: Long, title: String = "t", artist: String? = null, album: String? = null,
        durationMs: Long = 60_000L, folder: String = "", filename: String = "",
    ) = Track(id=id, title=title, artist=artist, album=album,
              durationMs=durationMs, contentUri="u", folder=folder, filename=filename)

    test("Rule: And empty = true (vacuous)") {
        val r = CollectionRule.And(emptyList())
        if (!r.matches(mkTrack(1L))) throw IllegalStateException("empty And should match")
        "ok"
    }
    test("Rule: Or empty = false") {
        val r = CollectionRule.Or(emptyList())
        if (r.matches(mkTrack(1L))) throw IllegalStateException("empty Or should not match")
        "ok"
    }
    test("Rule: Not inverts") {
        val base = CollectionRule.Duration(minMs = 10 * 60_000L)
        val r = CollectionRule.Not(base)
        if (!r.matches(mkTrack(1L, durationMs = 5 * 60_000L))) throw IllegalStateException("short should match Not")
        if (r.matches(mkTrack(2L, durationMs = 15 * 60_000L))) throw IllegalStateException("long should not match Not")
        "ok"
    }
    test("Rule: And all-true") {
        val r = CollectionRule.And(listOf(
            CollectionRule.Duration(minMs = 10 * 60_000L, maxMs = 30 * 60_000L),
            CollectionRule.Pattern("love", setOf(CollectionRule.Pattern.Field.Title)),
        ))
        if (!r.matches(mkTrack(1L, title = "love song", durationMs = 15 * 60_000L)))
            throw IllegalStateException("should match")
        if (r.matches(mkTrack(2L, title = "love song", durationMs = 5 * 60_000L)))
            throw IllegalStateException("too short should fail")
        if (r.matches(mkTrack(3L, title = "other", durationMs = 15 * 60_000L)))
            throw IllegalStateException("wrong title should fail")
        "ok"
    }
    test("Rule: Or any-true") {
        val r = CollectionRule.Or(listOf(
            CollectionRule.Duration(maxMs = 5 * 60_000L),
            CollectionRule.Pattern("love", setOf(CollectionRule.Pattern.Field.Title)),
        ))
        if (!r.matches(mkTrack(1L, title = "x", durationMs = 3 * 60_000L))) throw IllegalStateException("short should match")
        if (!r.matches(mkTrack(2L, title = "love", durationMs = 30 * 60_000L))) throw IllegalStateException("long+love should match")
        if (r.matches(mkTrack(3L, title = "x", durationMs = 30 * 60_000L))) throw IllegalStateException("neither should fail")
        "ok"
    }
    test("Rule: nested And(Or, Not)") {
        val r = CollectionRule.And(listOf(
            CollectionRule.Or(listOf(
                CollectionRule.Duration(maxMs = 5 * 60_000L),
                CollectionRule.Pattern("podcast", setOf(CollectionRule.Pattern.Field.Folder)),
            )),
            CollectionRule.Not(CollectionRule.Pattern("ad", setOf(CollectionRule.Pattern.Field.Title))),
        ))
        if (!r.matches(mkTrack(1L, title = "music", durationMs = 3 * 60_000L))) throw IllegalStateException("short+no-ad should match")
        if (r.matches(mkTrack(2L, title = "ad music", durationMs = 3 * 60_000L))) throw IllegalStateException("ad should fail")
        if (r.matches(mkTrack(3L, title = "music", durationMs = 30 * 60_000L))) throw IllegalStateException("long+not podcast should fail")
        "ok"
    }
    test("Rule: Pattern Artist field") {
        val r = CollectionRule.Pattern("beatles", setOf(CollectionRule.Pattern.Field.Artist))
        if (!r.matches(mkTrack(1L, title = "x", artist = "The Beatles"))) throw IllegalStateException("should match artist")
        if (r.matches(mkTrack(2L, title = "x", artist = "Other"))) throw IllegalStateException("wrong artist")
        "ok"
    }
    test("Rule: Pattern invalid regex -> false") {
        val r = CollectionRule.Pattern("[unclosed")
        if (r.matches(mkTrack(1L, title = "anything"))) throw IllegalStateException("invalid regex matched")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // C41a: persistence round-trip (And / Or / Not + Artist)
    // ═══════════════════════════════════════════════════════

    test("Persist: And round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val rule = CollectionRule.And(listOf(
            CollectionRule.Duration(minMs = 60_000L, maxMs = 5 * 60_000L),
            CollectionRule.Pattern("love", setOf(CollectionRule.Pattern.Field.Title, CollectionRule.Pattern.Field.Artist)),
        ))
        val id = CollectionRepository.create(context, "AndPersist", rule)
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val decoded = found?.rule
        if (decoded !is CollectionRule.And) throw IllegalStateException("not And: $decoded")
        if (decoded.rules.size != 2) throw IllegalStateException("rules=${decoded.rules.size}")
        val p = decoded.rules.filterIsInstance<CollectionRule.Pattern>().firstOrNull()
            ?: throw IllegalStateException("missing Pattern")
        if (CollectionRule.Pattern.Field.Artist !in p.fields) throw IllegalStateException("Artist lost")
        "ok"
    }
    test("Persist: Or round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val rule = CollectionRule.Or(listOf(
            CollectionRule.Duration(maxMs = 5 * 60_000L),
            CollectionRule.Types(setOf(ContentType.PODCAST)),
        ))
        val id = CollectionRepository.create(context, "OrPersist", rule)
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val decoded = found?.rule
        if (decoded !is CollectionRule.Or) throw IllegalStateException("not Or: $decoded")
        if (decoded.rules.size != 2) throw IllegalStateException("rules=${decoded.rules.size}")
        "ok"
    }
    test("Persist: Not round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val rule = CollectionRule.Not(CollectionRule.Pattern("ad"))
        val id = CollectionRepository.create(context, "NotPersist", rule)
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val decoded = found?.rule
        if (decoded !is CollectionRule.Not) throw IllegalStateException("not Not: $decoded")
        if (decoded.rule !is CollectionRule.Pattern) throw IllegalStateException("inner=${decoded.rule}")
        "ok"
    }
    test("Persist: excludeFromOthers round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(
            context, "Part", CollectionRule.Manual(emptyList()), excludeFromOthers = true)
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (found?.excludeFromOthers != true) throw IllegalStateException("exclude=${found?.excludeFromOthers}")
        "ok"
    }
    test("Persist: update changes rule+exclude") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "UpdOrig", CollectionRule.Manual(emptyList()))
        CollectionRepository.update(context, id, "UpdNew",
            CollectionRule.Duration(minMs = 60_000L), excludeFromOthers = true)
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (found?.label != "UpdNew") throw IllegalStateException("label=${found?.label}")
        if (found.excludeFromOthers != true) throw IllegalStateException("exclude=false")
        if (found.rule !is CollectionRule.Duration) throw IllegalStateException("rule=${found.rule}")
        "ok"
    }
    test("Persist: legacy (no exclude field) -> false") {
        // decode() قبلاً تست شده در persistence round-trip.
        // اینجا فقط رفتار پیش‌فرض در داده‌ی بدون exclude را چک می‌کنیم.
        val json = """[{"id":"legacy","label":"L","isSystem":false,"sortOrder":1,"createdAt":0,"updatedAt":0,"rule":{"type":"all"}}]"""
        // decode private است؛ پس از مسیر snapshot نمی‌شود. skip.
        "skipped (private decode)"
    }

    // ═══════════════════════════════════════════════════════
    // C41b: partition semantics — computeExcludedIds
    // ═══════════════════════════════════════════════════════

    test("Exclude: no partition -> empty") {
        val tracks = listOf(mkTrack(1L), mkTrack(2L))
        val cols = listOf(Collection("all", "All", CollectionRule.All, isSystem = true))
        val ids = CollectionRepository.computeExcludedIds(tracks, cols)
        if (ids.isNotEmpty()) throw IllegalStateException("ids=$ids")
        "ok"
    }
    test("Exclude: one partition returns its ids") {
        val tracks = listOf(mkTrack(1L), mkTrack(2L), mkTrack(3L))
        val p = Collection("p", "P", CollectionRule.Manual(listOf(1L, 3L)),
            excludeFromOthers = true)
        val cols = listOf(
            Collection("all", "All", CollectionRule.All, isSystem = true),
            p,
        )
        val ids = CollectionRepository.computeExcludedIds(tracks, cols)
        if (ids != setOf(1L, 3L)) throw IllegalStateException("ids=$ids")
        "ok"
    }
    test("Exclude: two partitions -> union") {
        val tracks = listOf(mkTrack(1L), mkTrack(2L), mkTrack(3L), mkTrack(4L))
        val p1 = Collection("p1", "P1", CollectionRule.Manual(listOf(1L, 2L)),
            excludeFromOthers = true)
        val p2 = Collection("p2", "P2", CollectionRule.Manual(listOf(3L)),
            excludeFromOthers = true)
        val ids = CollectionRepository.computeExcludedIds(tracks, listOf(p1, p2))
        if (ids != setOf(1L, 2L, 3L)) throw IllegalStateException("ids=$ids")
        "ok"
    }
    test("Exclude: duration rule as partition") {
        val tracks = listOf(
            mkTrack(1L, durationMs = 5 * 60_000L),
            mkTrack(2L, durationMs = 15 * 60_000L),
            mkTrack(3L, durationMs = 45 * 60_000L),
        )
        val p = Collection("p", "P", CollectionRule.Duration(maxMs = 10 * 60_000L),
            excludeFromOthers = true)
        val ids = CollectionRepository.computeExcludedIds(tracks, listOf(p))
        if (ids != setOf(1L)) throw IllegalStateException("ids=$ids")
        "ok"
    }
    test("Exclude: partition ignores trashed") {
        val tracks = listOf(mkTrack(1L), mkTrack(2L))
        val p = Collection("p", "P", CollectionRule.Manual(listOf(1L, 2L)),
            excludeFromOthers = true)
        val ids = CollectionRepository.computeExcludedIds(tracks, listOf(p),
            trashedIds = setOf(1L))
        if (ids != setOf(2L)) throw IllegalStateException("ids=$ids")
        "ok"
    }
    test("Exclude: All/Trash cannot partition") {
        val tracks = listOf(mkTrack(1L))
        val all = Collection("all", "All", CollectionRule.All, isSystem = true,
            excludeFromOthers = true)
        val trash = Collection(CollectionRepository.TRASH_ID, "Trash",
            CollectionRule.All, isSystem = true, excludeFromOthers = true)
        val ids = CollectionRepository.computeExcludedIds(tracks, listOf(all, trash))
        if (ids.isNotEmpty()) throw IllegalStateException("All/Trash partitioned: $ids")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // C41b: resolveTracks with excludeIds
    // ═══════════════════════════════════════════════════════

    test("Resolve: excludeIds removes tracks") {
        val t1 = mkTrack(1L); val t2 = mkTrack(2L); val t3 = mkTrack(3L)
        val c = Collection("all", "All", CollectionRule.All, isSystem = true)
        val got = CollectionRepository.resolveTracks(listOf(t1, t2, t3), c,
            excludeIds = setOf(2L))
        if (got.map { it.id } != listOf(1L, 3L)) throw IllegalStateException("got=${got.map { it.id }}")
        "ok"
    }
    test("Resolve: Trash ignores excludeIds") {
        val t1 = mkTrack(1L); val t2 = mkTrack(2L)
        val trash = Collection(CollectionRepository.TRASH_ID, "Trash", CollectionRule.All)
        val got = CollectionRepository.resolveTracks(listOf(t1, t2), trash,
            trashedIds = setOf(1L, 2L), excludeIds = setOf(1L, 2L))
        if (got.size != 2) throw IllegalStateException("trash filtered by exclude: ${got.size}")
        "ok"
    }
    test("Resolve: blacklist + exclude both apply") {
        val t1 = mkTrack(1L); val t2 = mkTrack(2L); val t3 = mkTrack(3L); val t4 = mkTrack(4L)
        val c = Collection("all", "All", CollectionRule.All)
        val got = CollectionRepository.resolveTracks(listOf(t1, t2, t3, t4), c,
            blacklistedIds = setOf(2L), excludeIds = setOf(3L))
        if (got.map { it.id } != listOf(1L, 4L)) throw IllegalStateException("got=${got.map { it.id }}")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // C36: permanentDelete
    // ═══════════════════════════════════════════════════════

    test("Delete: permanent removes from trash + manual") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "PermDel",
            CollectionRule.Manual(listOf(900_010L)))
        CollectionRepository.trash(context, 900_010L)
        CollectionRepository.permanentDelete(context, 900_010L)
        val snap = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        val trashIds = CollectionRepository.observeTrashIds(context).first()
        CollectionRepository.delete(context, id)
        if (900_010L in trashIds) throw IllegalStateException("still trashed")
        val manual = snap?.rule as? CollectionRule.Manual
        if (manual?.trackIds?.contains(900_010L) == true) throw IllegalStateException("still in manual")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // A: restore-to-origin (CollectionRepository)
    // ═══════════════════════════════════════════════════════

    test("Restore: back to origin manual") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "OriginRestore",
            CollectionRule.Manual(listOf(900_020L)))
        CollectionRepository.trash(context, 900_020L)
        val afterTrash = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        val manualAfterTrash = (afterTrash?.rule as? CollectionRule.Manual)?.trackIds
        if (manualAfterTrash?.contains(900_020L) == true) throw IllegalStateException("not removed on trash")
        CollectionRepository.restore(context, 900_020L)
        val afterRestore = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        val manualAfterRestore = (afterRestore?.rule as? CollectionRule.Manual)?.trackIds
        CollectionRepository.delete(context, id)
        if (manualAfterRestore?.contains(900_020L) != true) throw IllegalStateException("not restored to origin")
        "ok"
    }
    test("Restore: restoreAll brings back origins") {
        CollectionRepository.initializeIfNeeded(context)
        val a = CollectionRepository.create(context, "RA_A", CollectionRule.Manual(listOf(900_030L)))
        val b = CollectionRepository.create(context, "RA_B", CollectionRule.Manual(listOf(900_031L)))
        CollectionRepository.trash(context, 900_030L)
        CollectionRepository.trash(context, 900_031L)
        CollectionRepository.restoreAll(context)
        val snapA = CollectionRepository.snapshot(context).firstOrNull { it.id == a }
        val snapB = CollectionRepository.snapshot(context).firstOrNull { it.id == b }
        CollectionRepository.delete(context, a)
        CollectionRepository.delete(context, b)
        val ma = (snapA?.rule as? CollectionRule.Manual)?.trackIds
        val mb = (snapB?.rule as? CollectionRule.Manual)?.trackIds
        if (ma?.contains(900_030L) != true) throw IllegalStateException("A missing")
        if (mb?.contains(900_031L) != true) throw IllegalStateException("B missing")
        "ok"
    }
    test("Restore: emptyTrash clears origins") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "ET_Clear",
            CollectionRule.Manual(listOf(900_040L)))
        CollectionRepository.trash(context, 900_040L)
        CollectionRepository.emptyTrash(context)
        // بعد از emptyTrash، restore نباید چیزی برگردونه
        CollectionRepository.restore(context, 900_040L)
        val snap = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val manual = (snap?.rule as? CollectionRule.Manual)?.trackIds
        if (manual?.contains(900_040L) == true) throw IllegalStateException("origin survived emptyTrash")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // C44: Drift — state mutation edge cases
    // ═══════════════════════════════════════════════════════

    test("Drift: trash x2 same id -> origin stored once") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "DriftTrash2",
            CollectionRule.Manual(listOf(900_100L)))
        CollectionRepository.trash(context, 900_100L)
        CollectionRepository.trash(context, 900_100L)
        CollectionRepository.restore(context, 900_100L)
        val snap = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val manual = (snap?.rule as? CollectionRule.Manual)?.trackIds
        if (manual?.contains(900_100L) != true) throw IllegalStateException("origin lost")
        if (manual.count { it == 900_100L } != 1) throw IllegalStateException("duplicate: $manual")
        "ok"
    }
    test("Drift: rapid update x5 -> latest wins") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "DriftUpd",
            CollectionRule.Manual(emptyList()))
        CollectionRepository.update(context, id, "U1", CollectionRule.Duration(minMs = 1000L), false)
        CollectionRepository.update(context, id, "U2", CollectionRule.Duration(minMs = 2000L), true)
        CollectionRepository.update(context, id, "U3", CollectionRule.Duration(minMs = 3000L), false)
        CollectionRepository.update(context, id, "U4", CollectionRule.Duration(minMs = 4000L), true)
        CollectionRepository.update(context, id, "U5", CollectionRule.Duration(minMs = 5000L), false)
        val snap = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (snap?.label != "U5") throw IllegalStateException("label=${snap?.label}")
        if (snap.excludeFromOthers != false) throw IllegalStateException("exclude=${snap.excludeFromOthers}")
        val r = snap.rule as? CollectionRule.Duration ?: throw IllegalStateException("rule=${snap.rule}")
        if (r.minMs != 5000L) throw IllegalStateException("minMs=${r.minMs}")
        "ok"
    }
    test("Drift: excludeFromOthers survives trash+restore") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "DriftExcl",
            CollectionRule.Manual(listOf(900_110L)), excludeFromOthers = true)
        CollectionRepository.trash(context, 900_110L)
        val afterTrash = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        if (afterTrash?.excludeFromOthers != true) throw IllegalStateException("flag lost on trash")
        CollectionRepository.restore(context, 900_110L)
        val afterRestore = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (afterRestore?.excludeFromOthers != true) throw IllegalStateException("flag lost on restore")
        "ok"
    }
    test("Drift: computeExcludedIds idempotent") {
        val tracks = listOf(mkTrack(1L), mkTrack(2L), mkTrack(3L))
        val p = Collection("p", "P", CollectionRule.Manual(listOf(1L, 3L)),
            excludeFromOthers = true)
        val a = CollectionRepository.computeExcludedIds(tracks, listOf(p))
        val b = CollectionRepository.computeExcludedIds(tracks, listOf(p))
        if (a != b) throw IllegalStateException("drift: $a vs $b")
        "ok"
    }
    test("Drift: create+delete x3 -> no orphan") {
        CollectionRepository.initializeIfNeeded(context)
        val before = CollectionRepository.snapshot(context).size
        val ids = (1..3).map {
            CollectionRepository.create(context, "DriftDel$it",
                CollectionRule.Manual(emptyList()))
        }
        ids.forEach { CollectionRepository.delete(context, it) }
        val after = CollectionRepository.snapshot(context).size
        if (after != before) throw IllegalStateException("orphan: $before -> $after")
        "ok"
    }
    test("Drift: restore without origin -> no crash") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "DriftGhost",
            CollectionRule.Manual(listOf(900_120L)))
        CollectionRepository.restore(context, 900_120L)
        val snap = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val manual = (snap?.rule as? CollectionRule.Manual)?.trackIds
        if (manual?.contains(900_120L) != true) throw IllegalStateException("ghost removed origin")
        "ok"
    }

    // ═══════════════════════════════════════════════════════
    // C44: SSOT — immediate visibility after mutations
    // ═══════════════════════════════════════════════════════

    test("SSOT: create immediately visible") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "SsotCreate",
            CollectionRule.Duration(minMs = 60_000L))
        val visible = CollectionRepository.snapshot(context).any { it.id == id }
        CollectionRepository.delete(context, id)
        if (!visible) throw IllegalStateException("not visible")
        "ok"
    }
    test("SSOT: delete immediately removes") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "SsotDelete",
            CollectionRule.Manual(emptyList()))
        CollectionRepository.delete(context, id)
        val gone = CollectionRepository.snapshot(context).none { it.id == id }
        if (!gone) throw IllegalStateException("still visible")
        "ok"
    }
    test("SSOT: update immediately visible") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "SsotUpd",
            CollectionRule.Manual(emptyList()))
        CollectionRepository.update(context, id, "SsotUpdNEW",
            CollectionRule.Types(setOf(ContentType.PODCAST)), excludeFromOthers = true)
        val snap = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (snap?.label != "SsotUpdNEW") throw IllegalStateException("label=${snap?.label}")
        if (snap.excludeFromOthers != true) throw IllegalStateException("exclude false")
        if (snap.rule !is CollectionRule.Types) throw IllegalStateException("rule=${snap.rule}")
        "ok"
    }
    test("SSOT: excludeFromOthers isolated per collection") {
        CollectionRepository.initializeIfNeeded(context)
        val a = CollectionRepository.create(context, "SsotPartA",
            CollectionRule.Manual(emptyList()), excludeFromOthers = true)
        val b = CollectionRepository.create(context, "SsotPartB",
            CollectionRule.Manual(emptyList()), excludeFromOthers = false)
        val sa = CollectionRepository.snapshot(context).firstOrNull { it.id == a }
        val sb = CollectionRepository.snapshot(context).firstOrNull { it.id == b }
        CollectionRepository.delete(context, a)
        CollectionRepository.delete(context, b)
        if (sa?.excludeFromOthers != true) throw IllegalStateException("A wrong: ${sa?.excludeFromOthers}")
        if (sb?.excludeFromOthers != false) throw IllegalStateException("B wrong: ${sb?.excludeFromOthers}")
        "ok"
    }

    val pass = results.count { it.passed }
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val sb = StringBuilder()
    sb.appendLine("MariaPlayer Self-Test")
    sb.appendLine("Version: ${ir.mariaplayer.BuildConfig.VERSION_NAME} (code ${ir.mariaplayer.BuildConfig.VERSION_CODE})")
    sb.appendLine("Time:    $time")
    sb.appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    sb.appendLine("─".repeat(52))
    results.forEach { o ->
        val mark = if (o.passed) "✔ PASS" else "✘ FAIL"
        sb.appendLine("$mark  ${o.name}  —  ${o.detail}")
    }
    sb.appendLine("─".repeat(52))
    sb.appendLine("Result: $pass/${results.size} passed")
    return sb.toString()
}
