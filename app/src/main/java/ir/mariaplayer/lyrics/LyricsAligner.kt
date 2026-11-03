package ir.mariaplayer.lyrics

import ir.mariaplayer.asr.SherpaEngine

/**
 * PR-L35b: forced alignment — Levenshtein text-to-timeline.
 *
 * ASR token timings are reliable; ASR token text is not.
 * Network lyrics are text-accurate; they lack timing.
 * This bridges them:
 *
 *   input 1: accurateText (line-separated, from web/paste/user)
 *   input 2: asrTokens     (wrong text, correct timing)
 *   output : LRC with correct text and ASR-derived times
 *
 * Algorithm: character-level Levenshtein. We collapse ASR tokens to a
 * char stream (remembering token per char) and the accurate lyrics to
 * another char stream (remembering line per char). After DP alignment,
 * each accurate char maps to an ASR char → token → time. For each line
 * we take the earliest matched char's time. This tolerates ASR typos
 * (substitution cost = 1) so text accuracy is preserved regardless of
 * how badly the model misheard the audio.
 *
 * Memory: O(n*m) ints. Guarded by MAX_DP_CELLS to avoid OOM on huge
 * inputs (typical 3-min song: ~2000 acc chars × ~3000 tokens = 6M cells).
 */
object LyricsAligner {

    data class AlignedLine(val timeMs: Long, val text: String)

    private const val MAX_DP_CELLS = 10_000_000L

    fun align(
        accurateText: String,
        asrTokens: List<SherpaEngine.TokenTiming>,
    ): List<AlignedLine> {
        val lines = accurateText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty() || asrTokens.isEmpty()) return emptyList()

        // ASR char stream (with token index per char)
        val asrChars = StringBuilder()
        val charToToken = ArrayList<Int>(asrTokens.size * 4)
        for ((tokIdx, tok) in asrTokens.withIndex()) {
            val cleaned = tok.token
                .replace("\u2581", " ")
                .replace("\u0120", " ")
                .replace("<space>", " ")
            for (c in cleaned) {
                asrChars.append(c)
                charToToken.add(tokIdx)
            }
        }
        if (asrChars.isEmpty()) return emptyList()

        // Accurate char stream (line boundaries recorded)
        val accChars = StringBuilder()
        val lineStarts = ArrayList<Int>(lines.size)
        for (line in lines) {
            lineStarts.add(accChars.length)
            accChars.append(line)
        }

        val n = accChars.length
        val m = asrChars.length
        if (n == 0 || m == 0) return emptyList()
        if (n.toLong() * m.toLong() > MAX_DP_CELLS) return emptyList()

        // Levenshtein DP
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            val ac = accChars[i - 1]
            val row = dp[i]
            val prev = dp[i - 1]
            for (j in 1..m) {
                val cost = if (ac == asrChars[j - 1]) 0 else 1
                var best = prev[j] + 1          // delete
                val ins = row[j - 1] + 1        // insert
                if (ins < best) best = ins
                val sub = prev[j - 1] + cost    // substitute
                if (sub < best) best = sub
                row[j] = best
            }
        }

        // Backtrace
        val accToAsr = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val cost = if (accChars[i - 1] == asrChars[j - 1]) 0 else 1
            when {
                dp[i][j] == dp[i - 1][j - 1] + cost -> {
                    accToAsr[i - 1] = j - 1
                    i--; j--
                }
                dp[i][j] == dp[i - 1][j] + 1 -> i--
                else -> j--
            }
        }

        // Map each line to its earliest matched token's start time
        val out = ArrayList<AlignedLine>(lines.size)
        for (lineIdx in lines.indices) {
            val start = lineStarts[lineIdx]
            val end = if (lineIdx + 1 < lineStarts.size) lineStarts[lineIdx + 1] else n
            var bestTok = -1
            for (pos in start until end) {
                val asrPos = accToAsr[pos]
                if (asrPos >= 0 && asrPos < charToToken.size) {
                    bestTok = charToToken[asrPos]
                    break
                }
            }
            if (bestTok >= 0) {
                val t = asrTokens[bestTok].startSec
                val ms = (t * 1000f).toLong().coerceAtLeast(0L)
                out.add(AlignedLine(ms, lines[lineIdx]))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    fun toLrc(alignments: List<AlignedLine>): String {
        val sb = StringBuilder()
        for (a in alignments) {
            if (sb.isNotEmpty()) sb.append('\n')
            val sec = a.timeMs / 1000
            val min = sec / 60
            val s = sec % 60
            val cs = (a.timeMs % 1000) / 10
            sb.append("[%02d:%02d.%02d]%s".format(min, s, cs, a.text))
        }
        return sb.toString()
    }
}
