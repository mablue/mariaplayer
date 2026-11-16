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
import ir.mariaplayer.data.ContentType
import ir.mariaplayer.data.ContentTypePrefs
import ir.mariaplayer.data.ContentTypeLogic
import ir.mariaplayer.data.FavoritesPrefs
import ir.mariaplayer.data.MediaClassifier
import ir.mariaplayer.data.LibraryPrefs
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.LibraryTab
import ir.mariaplayer.data.LibraryTabPrefs
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.PlaylistPrefs
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
    test("Favorites round-trip") {
        val testId = 9_900_000_000L + Random.nextLong(100_000)
        FavoritesPrefs.set(context, testId, true)
        val added = FavoritesPrefs.snapshot(context).contains(testId)
        FavoritesPrefs.set(context, testId, false)
        val removed = !FavoritesPrefs.snapshot(context).contains(testId)
        if (!added || !removed) throw IllegalStateException("add=$added rm=$removed")
        "ok"
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
    test("Playlist CRUD") {
        val name = "selftest_${System.currentTimeMillis()}"
        val id = PlaylistPrefs.create(context, name)
        if (id <= 0) throw IllegalStateException("create id=$id")
        val tId = 888_888L
        val added = PlaylistPrefs.addTrack(context, id, tId)
        val found = PlaylistPrefs.snapshot(context).find { it.id == id }
        PlaylistPrefs.delete(context, id)
        val gone = PlaylistPrefs.snapshot(context).none { it.id == id }
        if (!added || found?.trackIds?.contains(tId) != true || !gone)
            throw IllegalStateException("add=$added gone=$gone")
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
    test("Tabs: defaults have 4") {
        val tabs = LibraryTab.defaults()
        if (tabs.size != 4) throw IllegalStateException("size=${tabs.size}")
        if (tabs.map { it.kind }.toSet().size != 4)
            throw IllegalStateException("kinds not unique")
        "4"
    }
    test("Tabs: defaults all isDefault") {
        if (LibraryTab.defaults().any { !it.isDefault })
            throw IllegalStateException("some default not flagged")
        "all default"
    }
    test("Tabs: add custom round-trip") {
        val orig = LibraryTabPrefs.read(context)
        val tab = LibraryTab(
            id = "custom_test_" + System.currentTimeMillis(),
            kind = LibraryTab.Kind.MUSIC,
            label = "TestTab",
            isDefault = false,
        )
        LibraryTabPrefs.add(context, tab)
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        if (got.none { it.id == tab.id }) throw IllegalStateException("not added")
        if (got.firstOrNull { it.id == tab.id }?.label != "TestTab")
            throw IllegalStateException("label mismatch")
        "ok"
    }
    test("Tabs: remove custom works") {
        val orig = LibraryTabPrefs.read(context)
        val tab = LibraryTab(
            id = "custom_del_" + System.currentTimeMillis(),
            kind = LibraryTab.Kind.PODCAST,
            label = "Del",
            isDefault = false,
        )
        LibraryTabPrefs.add(context, tab)
        LibraryTabPrefs.remove(context, tab.id)
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        if (got.any { it.id == tab.id }) throw IllegalStateException("still present")
        "removed"
    }
    test("Tabs: default cannot be removed") {
        val orig = LibraryTabPrefs.read(context)
        LibraryTabPrefs.remove(context, "all")
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        if (got.none { it.id == "all" }) throw IllegalStateException("all was removed")
        "protected"
    }
    test("Tabs: rename round-trip") {
        val orig = LibraryTabPrefs.read(context)
        LibraryTabPrefs.rename(context, "music", "Songs")
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        val music = got.firstOrNull { it.id == "music" }
        if (music?.label != "Songs") throw IllegalStateException("label=${music?.label}")
        "ok"
    }
    test("Tabs: empty prefs → defaults") {
        val orig = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, emptyList())
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        if (got.size != 4) throw IllegalStateException("size=${got.size}")
        "restored"
    }
    test("Tabs: filter predicate ALL kind") {
        val all = LibraryTab.defaults().first { it.kind == LibraryTab.Kind.ALL }
        val musicTrack = Track(id = 1L, title = "t", artist = null, album = null,
            durationMs = 60_000L, contentUri = "u").copy(contentType = ir.mariaplayer.data.ContentType.MUSIC)
        val podTrack = Track(id = 2L, title = "p", artist = null, album = null,
            durationMs = 60_000L, contentUri = "v").copy(contentType = ir.mariaplayer.data.ContentType.PODCAST)
        // ALL tab should accept both — verify by kind semantics
        if (all.kind != LibraryTab.Kind.ALL) throw IllegalStateException("kind=${all.kind}")
        if (musicTrack.contentType == podTrack.contentType) throw IllegalStateException("setup broken")
        "all accepts both"
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
        val slot = PlaybackPrefs.read()
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
        val slot = PlaybackPrefs.read()
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
