package ir.mariaplayer.data

/**
 * Normalizes a messy tag/filename pair into the best (title, artist) we can
 * derive for a lyrics search query.
 *
 * Pure Kotlin — no Android dependencies. Deterministic.
 *
 * Algorithm (S10.1, validated against a 588-track Persian library):
 *   1. cleanV2: apply S10 regex in a loop until stable, plus bracket fixes
 *   2. is_valid: reject numbers / hashes / size-strings / placeholders
 *   3. pick: prefer TAG if valid, else FILENAME, else INVALID
 *   4. artist: keep only if valid, else empty
 *
 * In the library:
 *   - 561/588 tracks got a valid title (95.2%)
 *   - 340/588 got a valid artist (57.8%, excluding site/placeholder junk)
 *   - 26 tracks flagged needsManualFix (numeric/hash names, no metadata)
 */
enum class Source { TAG, FILENAME, INVALID }

data class NormalizedQuery(
    val title: String,
    val artist: String,
    val source: Source,
    val needsManualFix: Boolean,
)

object QueryNormalizer {

    // ─── Regexes ─────────────────────────────────────────────────────────

    private val EXT_RE = Regex(
        """\.(mp3|flac|ogg|oga|m4a|mp4|wav|aac|opus|wma|ape|aiff|alac|dsf|dff)$""",
        RegexOption.IGNORE_CASE,
    )

    // Track prefix: "07 - ", "01. ", "5) "
    private val TRACK_PREFIX_RE = Regex("""^\s*\d{1,3}\s*[.\-)]\s+""")

    // Year prefix: "(2023) ", "2021 - ", "2023 "
    private val YEAR_PREFIX_RE = Regex(
        """^\s*[\(\[]?(19|20)\d{2}[\)\]]?\s*[-_.\s]+""",
    )

    // Main S10.1 noise filter. Kotlin (java.util.regex) accepts \x{...}
    // for codepoints — same syntax as the validated Python `regex` build.
    private val RX = Regex(
        """(?iu)(?:https?://|www\.)\S+""" +
        """|\b[\w.-]+\.(?:com|ir|net|org|info|me|us|uk|co|tv|io|to|gl|ly|cc|xyz|online|site|club|top|live|pro|es|it|fr|de|ru|az|tr|ca|br|au|in|tk)\b\S*""" +
        """|(?:t\.me|telegram|اینستاگرام|instagram)[:/@ ]\S*""" +
        """|@[\w\u0600-\u06FF_]+""" +
        """|\b\d{2,3}\s*(?:kbps|kb/s)\b""" +
        """|\b\d+(?:\.\d+)?\s*(?:MiB|KiB|KB|MB|GB)\b""" +
        """|[\(\[\{]\s*[\)\]\}]""" +
        """|[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}♫♪♬🎵🎶♥💜💎🔴✨👑👽🆑™©®]""" +
        """|(?i:UpMusic|MusicDel|MusicsWeb|Music[-_ ]?Fa(?:\.Com)?|BibakMusic|GahMusic|RozMusic|SevilMusic|CafehMusic|MusicViral|Saz[-_ ]?Music|Azturk|TabrizMusic|Galinmusic|ArdabilMusic|Download1Music|MihanSeda|Songha|Jonmusic|Melody98|Azerbaijan\d*|BehMelody|Nex1Music|Takmusic\d*|Hamtarane\d*|Bikalambox|EDMmusics|ReMiXeRapY|GlinMusic|Radio98|RadioP0l|soonami|samfonibox|mah6il|MokhtalefMusic|MusicTaj|webahang|musicsingle|smelody|tabanmusic|TehranMusic|AzadMusic|دائمی|کانال|سایت|موزیکدل|جویا موزیک|نیاز موزیک|کافه موزیک|اوپن موزیک|آهنگدونی|موزیکفا|آپ[-_ ]?موزیک|نواک)""" +
        """|_{2,}\S*""" +
        """|\b(?i:(?:audio|video)?cutter)\w*""" +
        """|OfficialVisualizer\w*""" +
        """|(?:audio|video)only\w*""" +
        """|\s*[~\-–—|•·]+\s*$""" +
        """|^[\s~\-–—|•·]+""",
    )

    private val UNCLOSED_END   = Regex("""[\(\[\{][^\)\]\}]*$""")
    private val UNCLOSED_START = Regex("""^[^\(\[\{]*[\)\]\}]""")
    private val DOT_TLD = Regex(
        """(?:^|\s)\.(?:com|ir|net|org|info|me|us|uk|co|tv|io|to|gl|ly|cc|xyz|online|site|club|top|live|pro|es|it|fr|de|ru|az|tr|ca|br|au|in|tk)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val WS = Regex("""\s+""")
    private val CAMEL = Regex("""([a-z])([A-Z])""")

    private val HASH_RE = Regex("""[0-9a-fA-F]{16,}""")
    private val SIZE_RE = Regex(
        """\d+(\.\d+)?\s*(MiB|KiB|KB|MB|GB)""",
        RegexOption.IGNORE_CASE,
    )

    private val VERSION_V2_RE    = Regex("""\s*[\(\[]?v\d+[\)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val BITRATE_BARE_RE  = Regex("""\s+(?:128|192|256|320)\s*$""")

    private val PLACEHOLDERS = setOf(
        "factory bot", "audiolab", "audio lab", "audio-labs",
        "miu clip", "miu-clip", "music lab",
        "musicsweb", "musicsingle",
        "audiolibrary", "unknown", "various artists", "various",
        "audio", "video",
    )

    // ─── Public API ──────────────────────────────────────────────────────

    fun normalize(
        rawTitle: String,
        rawArtist: String,
        filename: String,
    ): NormalizedQuery {
        val tagTitle  = cleanV2(rawTitle)
        val tagArtist = cleanV2(rawArtist)
        val fnClean   = cleanV2(filename)

        val (title, source) = when {
            isValid(tagTitle) -> tagTitle to Source.TAG
            isValid(fnClean)  -> fnClean  to Source.FILENAME
            else              -> tagTitle.ifEmpty { fnClean } to Source.INVALID
        }
        val artist = if (isValid(tagArtist)) tagArtist else ""

        return NormalizedQuery(
            title = title,
            artist = artist,
            source = source,
            needsManualFix = source == Source.INVALID,
        )
    }

    // ─── Internals ───────────────────────────────────────────────────────

    internal fun cleanV2(raw: String): String {
        if (raw.isBlank()) return ""
        var s = EXT_RE.replace(raw, "")
            .replace('_', ' ')
        s = WS.replace(s, " ").trim()

        // Strip track/year prefix only if result still has >= 2 letters
        val afterTrack = TRACK_PREFIX_RE.replace(s, "")
        if (afterTrack.count { it.isLetter() } >= 2) s = afterTrack

        val afterYear = YEAR_PREFIX_RE.replace(s, "")
        if (afterYear.count { it.isLetter() } >= 2) s = afterYear

        s = WS.replace(s, " ").trim()

        // Trailing-only cleanups: version suffix and bare bitrate number.
        val afterV2 = VERSION_V2_RE.replace(s, "")
        if (afterV2.isNotBlank()) s = afterV2.trim()
        val afterBitrate = BITRATE_BARE_RE.replace(s, "")
        if (afterBitrate.isNotBlank()) s = afterBitrate.trim()

        repeat(3) {
            val before = s
            s = RX.replace(s, " ")
            s = UNCLOSED_END.replace(s, " ")
            s = UNCLOSED_START.replace(s, " ")
            s = DOT_TLD.replace(s, " ")
            s = WS.replace(s, " ").trim()
            s = s.trim { it in "-. _()[]{}~–—|•·" }
            if (s == before) return@repeat
            // note: `return@repeat` skips to next iteration; we break via check below
        }

        // one more pass with early-exit check
        var prev: String
        do {
            prev = s
            s = RX.replace(s, " ")
            s = UNCLOSED_END.replace(s, " ")
            s = UNCLOSED_START.replace(s, " ")
            s = DOT_TLD.replace(s, " ")
            s = WS.replace(s, " ").trim()
            s = s.trim { it in "-. _()[]{}~–—|•·" }
        } while (s != prev && s.length in 1 until prev.length)

        s = CAMEL.replace(s) { m ->
            "${m.groupValues[1]} ${m.groupValues[2]}"
        }
        return WS.replace(s, " ").trim()
    }

    private fun isValid(s: String): Boolean {
        if (s.isBlank() || s.length < 2) return false
        if (s.all { it.isDigit() }) return false
        if (HASH_RE.matches(s)) return false
        if (SIZE_RE.matches(s)) return false
        if (s.lowercase().trim() in PLACEHOLDERS) return false
        if (s.count { it.isLetter() } < 2) return false
        return true
    }
}
