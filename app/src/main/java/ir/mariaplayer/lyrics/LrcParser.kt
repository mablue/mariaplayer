package ir.mariaplayer.lyrics

/**
 * Minimal LRC parser.
 * Handles: [mm:ss.xx], [mm:ss.xxx], [mm:ss], multiple timestamps per line.
 * Skips metadata tags like [ar:...], [ti:...] (no numeric match).
 */
data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {

    private val TAG_RE = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(raw: String): List<LrcLine> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<LrcLine>()
        for (line in raw.lineSequence()) {
            val matches = TAG_RE.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = TAG_RE.replace(line, "").trim()
            for (m in matches) {
                val min  = m.groupValues[1].toLongOrNull() ?: continue
                val sec  = m.groupValues[2].toLongOrNull() ?: continue
                val rawF = m.groupValues[3]
                val frac = when {
                    rawF.isEmpty()  -> 0L
                    rawF.length == 1 -> rawF.toLong() * 100
                    rawF.length == 2 -> rawF.toLong() * 10
                    else             -> rawF.toLong()
                }
                out += LrcLine(min * 60_000L + sec * 1_000L + frac, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the last line whose timestamp is <= positionMs, or -1. */
    fun activeIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 }
            else hi = mid - 1
        }
        return ans
    }
}
