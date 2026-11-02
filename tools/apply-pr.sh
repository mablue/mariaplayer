#!/data/data/com.termux/files/usr/bin/bash
# PR-L17: fix OOM — file-based lyrics + probe-based silence chunking
set -uo pipefail
cd ~/mariaplayer || exit 1

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YEL=$'\033[1;33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
STEP=0
step() { STEP=$((STEP+1)); echo; echo "${BOLD}${YEL}[$STEP] $1${NC}"; }
ok()   { echo "${GREEN}✔ $1${NC}"; }
fail() { echo; echo "${RED}${BOLD}✘ FAILED at step $STEP: $1${NC}"; echo "${RED}Nothing pushed.${NC}"; exit 1; }

# ────────── 1. verify LyricsFileStore ──────────
step "Verify LyricsFileStore (from previous run)"
[ -f app/src/main/java/ir/mariaplayer/lyrics/LyricsFileStore.kt ] \
  || fail "LyricsFileStore.kt missing — previous run didn't complete"
grep -q 'object LyricsFileStore' app/src/main/java/ir/mariaplayer/lyrics/LyricsFileStore.kt \
  || fail "LyricsFileStore content wrong"
ok "LyricsFileStore.kt present"

# ────────── 2. full rewrite LyricsCache with correct imports ──────────
step "Rewrite LyricsCache with proper imports"
cat > app/src/main/java/ir/mariaplayer/lyrics/LyricsCache.kt <<'KT'
package ir.mariaplayer.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyricsStore by preferencesDataStore(name = "lyrics_cache")

/**
 * PR-L17: DataStore holds ONLY small values (offset, translation language).
 * Lyric strings live in LyricsFileStore (plain files). This eliminates the
 * OOM path inside DataStoreImpl$writeActor where large payload writes were
 * allocating with <1% heap free.
 */
object LyricsCache {

    data class Cached(
        val synced: String?,
        val plain: String?,
        val offsetMs: Long,
    ) {
        val hasLyrics: Boolean get() = !synced.isNullOrBlank() || !plain.isNullOrBlank()
    }

    suspend fun read(context: Context, trackId: Long): Cached {
        val offset = context.lyricsStore.data.first()[
            longPreferencesKey("lyrics_${trackId}_offset")
        ] ?: 0L
        return Cached(
            synced = LyricsFileStore.readSynced(context, trackId),
            plain  = LyricsFileStore.readPlain(context, trackId),
            offsetMs = offset,
        )
    }

    suspend fun writeLyrics(
        context: Context,
        trackId: Long,
        synced: String?,
        plain: String?,
    ) {
        LyricsFileStore.writeSynced(context, trackId, synced)
        LyricsFileStore.writePlain(context, trackId, plain)
    }

    suspend fun clearLyrics(context: Context, trackId: Long) {
        LyricsFileStore.writeSynced(context, trackId, null)
        LyricsFileStore.writePlain(context, trackId, null)
    }

    suspend fun writeOffset(context: Context, trackId: Long, offsetMs: Long) {
        context.lyricsStore.edit { p ->
            p[longPreferencesKey("lyrics_${trackId}_offset")] = offsetMs.coerceIn(-500L, 500L)
        }
    }

    suspend fun readTranslation(context: Context, trackId: Long): Pair<String, String>? {
        val lang = context.lyricsStore.data.first()[
            stringPreferencesKey("lyrics_${trackId}_tr_lang")
        ] ?: return null
        val text = LyricsFileStore.readTranslation(context, trackId) ?: return null
        return lang to text
    }

    suspend fun writeTranslation(
        context: Context,
        trackId: Long,
        lang: String,
        text: String,
    ) {
        context.lyricsStore.edit { p ->
            p[stringPreferencesKey("lyrics_${trackId}_tr_lang")] = lang
        }
        LyricsFileStore.writeTranslation(context, trackId, text)
    }

    suspend fun clearTranslation(context: Context, trackId: Long) {
        context.lyricsStore.edit { p ->
            p.remove(stringPreferencesKey("lyrics_${trackId}_tr_lang"))
        }
        LyricsFileStore.writeTranslation(context, trackId, null)
    }
}
KT
grep -q 'import androidx.datastore.preferences.core.stringPreferencesKey' \
  app/src/main/java/ir/mariaplayer/lyrics/LyricsCache.kt \
  || fail "stringPreferencesKey import missing"
grep -q 'LyricsFileStore.readSynced' \
  app/src/main/java/ir/mariaplayer/lyrics/LyricsCache.kt \
  || fail "file store read missing"
grep -q 'LyricsFileStore.writeSynced' \
  app/src/main/java/ir/mariaplayer/lyrics/LyricsCache.kt \
  || fail "file store write missing"
ok "LyricsCache.kt rewritten with proper imports"

# ────────── 3. rewrite AsrManager: probe-based silence chunking ──────────
step "Rewrite AsrManager (bounded memory + silence-aware chunking)"
cat > app/src/main/java/ir/mariaplayer/asr/AsrManager.kt <<'KT'
package ir.mariaplayer.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * PR-L17: transcribe with bounded memory AND silence-aware chunk boundaries.
 *
 * Peak memory is one probe window (~chunkMs + 3s), not the whole track.
 * For each iteration:
 *   - decode [posMs, posMs + chunkMs + marginMs)  ← the "probe"
 *   - find the local energy minimum near chunkSamples
 *   - keep only [0, silenceAt), feed to sherpa, advance posMs to silenceAt
 * This preserves the sentence-aligned chunk boundaries that PR-L15b added,
 * without ever holding the entire file in a FloatArray.
 */
object AsrManager {

    data class AsrChunk(val startMs: Long, val text: String)

    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelDir: File,
        startMs: Long = 0L,
        totalMs: Long = 30_000L,
        chunkMs: Long = 5_000L,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        val chunks = transcribeFull(
            context, uri, modelDir, totalMs, chunkMs,
            startMs = startMs,
            onProgress = { p, _ -> onProgress(p) },
        )
        chunks.map { it.text }.filter { it.isNotBlank() }.joinToString(" ")
    }

    suspend fun transcribeFull(
        context: Context,
        uri: Uri,
        modelDir: File,
        totalMs: Long,
        chunkMs: Long = 15_000L,
        startMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): List<AsrChunk> = withContext(Dispatchers.Default) {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        require(totalMs > 0) { "totalMs must be > 0" }

        val chunkSamples = (chunkMs * 16000L / 1000L).toInt()
        val minChunkSamples = (5_000L * 16000L / 1000L).toInt()
        val probeMarginMs = 3_000L

        val eng = SherpaEngine(modelDir)
        val startedAt = System.currentTimeMillis()
        val result = ArrayList<AsrChunk>()

        try {
            var posMs = startMs
            val endMs = startMs + totalMs

            while (posMs < endMs - 500L) {
                currentCoroutineContext().ensureActive()
                val remainingMs = endMs - posMs
                val probeMs = minOf(chunkMs + probeMarginMs, remainingMs)

                val probe = try {
                    AudioDecoder.decodeToFloat(context, uri, posMs, probeMs)
                } catch (_: Throwable) {
                    FloatArray(0)
                }

                if (probe.isEmpty()) {
                    // Decode failure on this region — skip forward
                    posMs += chunkMs
                    continue
                }

                val (slice, advanceMs) = if (probe.size <= chunkSamples) {
                    probe to remainingMs
                } else {
                    val silenceAt = findSilenceNear(
                        probe, chunkSamples, windowMs = 1500, minChunk = minChunkSamples,
                    )
                    probe.copyOfRange(0, silenceAt) to (silenceAt * 1000L / 16000L)
                }

                val text = runCatching { eng.transcribe(slice).trim() }.getOrDefault("")
                result.add(AsrChunk(posMs, text))
                posMs += advanceMs

                val progress = ((posMs - startMs).toFloat() / totalMs).coerceIn(0f, 1f)
                val elapsed = System.currentTimeMillis() - startedAt
                val eta = if (progress > 0.05f)
                    (elapsed / progress - elapsed).toLong() else -1L
                val etaStr = if (eta > 1000) " · ${formatEta(eta)} left" else ""
                onProgress(progress, "${(progress * 100).toInt()}%$etaStr")
            }
        } finally {
            runCatching { eng.close() }
        }
        result
    }

    private fun findSilenceNear(
        samples: FloatArray,
        target: Int,
        windowMs: Int,
        minChunk: Int,
    ): Int {
        val window = 16000 * windowMs / 1000
        val lo = (target - window).coerceAtLeast(minChunk)
        val hi = (target + window).coerceAtMost(samples.size - 1)
        if (hi - lo < 2000) return target

        val step = 800  // 50 ms analysis frame
        var bestPos = target
        var bestRms = Float.MAX_VALUE
        var p = lo
        while (p + step <= hi) {
            var sum = 0.0
            for (i in 0 until step) {
                val v = samples[p + i].toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / step).toFloat()
            if (rms < bestRms) {
                bestRms = rms
                bestPos = p
            }
            p += step / 2
        }
        return bestPos.coerceIn(lo, hi)
    }

    fun toLrc(chunks: List<AsrChunk>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            if (c.text.isBlank()) continue
            val sec = c.startMs / 1000
            val min = sec / 60
            val s = sec % 60
            val cs = (c.startMs % 1000) / 10
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("[%02d:%02d.%02d]%s".format(min, s, cs, c.text))
        }
        return sb.toString()
    }

    private fun formatEta(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }
}
KT
grep -q 'decodeToFloat' app/src/main/java/ir/mariaplayer/asr/AsrManager.kt \
  || fail "decodeToFloat missing"
grep -q 'decodeFull' app/src/main/java/ir/mariaplayer/asr/AsrManager.kt \
  && fail "decodeFull still referenced — would OOM"
grep -q 'findSilenceNear' app/src/main/java/ir/mariaplayer/asr/AsrManager.kt \
  || fail "silence detection missing"
ok "AsrManager.kt rewritten"

# ────────── 4. stage ──────────
step "Stage changes"
git add app/src/main/java/ir/mariaplayer/lyrics/LyricsFileStore.kt \
        app/src/main/java/ir/mariaplayer/lyrics/LyricsCache.kt \
        app/src/main/java/ir/mariaplayer/asr/AsrManager.kt \
  || fail "git add failed"
git diff --cached --quiet && fail "nothing staged"
ok "staged"

# ────────── 5. commit ──────────
step "Commit"
git commit -m "fix(asr): file-based lyrics + probe silence chunking (PR-L17 OOM fix)" \
  || fail "commit failed"
ok "committed"

# ────────── 6. push ──────────
step "Sync + push"
git fetch origin main || fail "fetch failed"
git pull --rebase origin main || fail "rebase conflict — check git status"
git push origin main || fail "push rejected"
ok "pushed $(git log --oneline -1)"

# ────────── 7. build ──────────
step "Build + install"
echo "${GREEN}${BOLD}━━━ all green — building ━━━${NC}"
exec bash ~/mariaplayer/tools/get-apk.sh
