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
import ir.mariaplayer.data.FavoritesPrefs
import ir.mariaplayer.data.LibraryPrefs
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.PlaylistPrefs
import ir.mariaplayer.data.QueryNormalizer
import ir.mariaplayer.data.SortMode
import ir.mariaplayer.data.Source
import ir.mariaplayer.lyrics.LrcLine
import ir.mariaplayer.lyrics.LrcParser
import ir.mariaplayer.lyrics.LyricsCache
import ir.mariaplayer.lyrics.LyricsScorer
import kotlinx.coroutines.delay
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
        val summary = probes.joinToString(" · ") {
            "${it.name}=${if (it.ok) "\u2713" else "\u2717" + it.output.take(15)}"
        }
        val ok = probes.filter { it.ok }
        if (ok.isEmpty()) throw IllegalStateException(summary)
        "\u2713 ${ok.size}/${probes.size} | " + ok.first().output.take(20)
    }
    test("Theme default style") {
        val bl = VazirTypography.bodyLarge
        if (bl.fontSize.value <= 0f) throw IllegalStateException("unset")
        "bodyLarge=${bl.fontSize.value}sp"
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
