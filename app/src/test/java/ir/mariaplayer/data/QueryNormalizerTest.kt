package ir.mariaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryNormalizerTest {

    private fun cleanTitle(raw: String, expected: String) {
        val r = QueryNormalizer.normalize(raw, "", "")
        assertEquals("title($raw)", expected, r.title)
        assertEquals("source($raw)", Source.TAG, r.source)
    }

    private fun cleanBoth(rawT: String, rawA: String, expT: String, expA: String) {
        val r = QueryNormalizer.normalize(rawT, rawA, "x.mp3")
        assertEquals("title($rawT)", expT, r.title)
        assertEquals("artist($rawA)", expA, r.artist)
    }

    private fun assertFilenameFallback(rawT: String, rawA: String, rawFn: String, expT: String) {
        val r = QueryNormalizer.normalize(rawT, rawA, rawFn)
        assertEquals("fn($rawFn)", expT, r.title)
        assertEquals("src($rawFn)", Source.FILENAME, r.source)
    }

    private fun assertInvalid(rawT: String, rawA: String, rawFn: String) {
        val r = QueryNormalizer.normalize(rawT, rawA, rawFn)
        assertEquals("src invalid($rawT|$rawA|$rawFn)", Source.INVALID, r.source)
        assertTrue("manualFix($rawT|$rawA|$rawFn)", r.needsManualFix)
    }

    private fun assertArtistEmpty(rawA: String) {
        val r = QueryNormalizer.normalize("Valid Title", rawA, "x.mp3")
        assertEquals("expected empty artist for $rawA", "", r.artist)
    }

    // ─── Already clean ───────────────────────────────────────────────────

    @Test fun cleanAscii()        = cleanTitle("Hello World", "Hello World")
    @Test fun cleanPersian()      = cleanTitle("آهنگ زیبا", "آهنگ زیبا")
    @Test fun cleanMultiWord()    = cleanTitle("The Long Track Name", "The Long Track Name")
    @Test fun cleanWithDigits()   = cleanTitle("Blink-182", "Blink-182")
    @Test fun iWantToBreakFree()  = cleanTitle("I Want To Break Free", "I Want To Break Free")

    // ─── Underscore ──────────────────────────────────────────────────────

    @Test fun underscoreSimple()  = cleanTitle("Some_Song_Name", "Some Song Name")
    @Test fun underscorePersian() = cleanTitle("آهنگ_زیبا", "آهنگ زیبا")

    // ─── Year prefix ─────────────────────────────────────────────────────

    @Test fun yearParen()         = cleanTitle("(2023) Song", "Song")
    @Test fun yearDash()          = cleanTitle("2021 - Title", "Title")
    @Test fun yearSpace()         = cleanTitle("2023 aghasi", "aghasi")

    // ─── Track prefix ────────────────────────────────────────────────────

    @Test fun trackDot()          = cleanTitle("01. Title", "Title")
    @Test fun trackDash()         = cleanTitle("07 - Song", "Song")

    // ─── Bitrate ─────────────────────────────────────────────────────────

    @Test fun bitrateBare()       = cleanTitle("Song 320", "Song")
    @Test fun bitrateKbps()       = cleanTitle("Title (320kbps)", "Title")

    // ─── Empty brackets ──────────────────────────────────────────────────

    @Test fun emptyBracketsParen()   = cleanTitle("Mesle Filme ( )", "Mesle Filme")
    @Test fun emptyBracketsSquare()  = cleanTitle("Title [ ]", "Title")

    // ─── Persian site ────────────────────────────────────────────────────

    @Test fun persianSiteAhang()  = cleanTitle("آهنگدونی - Song", "Song")
    @Test fun persianSiteMuzikfa()= cleanTitle("موزیکفا Title", "Title")

    // ─── Latin site / domain ─────────────────────────────────────────────

    @Test fun latinSiteUpmusics() = cleanTitle("Song upmusics.com", "Song")
    @Test fun latinSiteWww()      = cleanTitle("Track www.Yazd-Music.com", "Track")

    // ─── Version suffix ──────────────────────────────────────────────────

    @Test fun versionV2()         = cleanTitle("Song V2", "Song")
    @Test fun versionRemastered() = cleanTitle("Title (Remastered 2011)", "Title")

    // ─── Extension ───────────────────────────────────────────────────────

    @Test fun extMp3()            = cleanTitle("Song.mp3", "Song")
    @Test fun extFlacUpper()      = cleanTitle("Track.FLAC", "Track")

    // ─── Numeric / hash / empty → invalid ────────────────────────────────

    @Test fun numericOnly()       = assertInvalid("12345", "", "")
    @Test fun hashLike()          = assertInvalid("a1b2c3d4e5f6a7b8", "", "")
    @Test fun emptyTitle()        = assertInvalid("", "", "")
    @Test fun whitespaceTitle()   = assertInvalid("   ", "", "")

    // ─── CamelCase ───────────────────────────────────────────────────────

    @Test fun camelSimple()       = cleanTitle("HelloWorld", "Hello World")
    @Test fun camelLonger()       = cleanTitle("SomeCamelCase", "Some Camel Case")

    // ─── Combo ───────────────────────────────────────────────────────────

    @Test fun comboAll()          = cleanTitle("07 - 2022 - Title - 320kbps.mp3", "Title")
    @Test fun comboPersianSite()  = cleanTitle("آهنگدونی_Song_Title", "Song Title")

    // ─── Bug fixes (S9 → S10.1) ──────────────────────────────────────────

    @Test fun bugElysium()        = cleanTitle("Elysium(Replaced)", "Elysium")
    @Test fun bugEvanBand()       = cleanBoth(
        "Delam Khast", "Evan Band @RozMusic.com", "Delam Khast", "Evan Band"
    )
    @Test fun bugAntaMo()         = cleanBoth(
        "Anta MoAlem (www.Yazd-Music.com)",
        "Saad Lamjarred (www.Yazd-Music.com)",
        "Anta Mo Alem", "Saad Lamjarred"
    )
    @Test fun bugMuzlyra()        = cleanTitle(
        "muzlyraYavaşçaRuhunaSarayım[muzce.com]", "muzlyra Yavaşça Ruhuna Sarayım"
    )

    // ─── Placeholder artists ─────────────────────────────────────────────

    @Test fun placeholderFactoryBot() = assertArtistEmpty("Factory Bot")
    @Test fun placeholderAudioLab()   = assertArtistEmpty("AudioLab")
    @Test fun placeholderAudioLabSp() = assertArtistEmpty("Audio Lab")
    @Test fun placeholderMusicsweb()  = assertArtistEmpty("musicsweb.ir")

    // ─── Filename fallback ───────────────────────────────────────────────

    @Test fun filenameFallback()  = assertFilenameFallback("", "", "01. Song.mp3", "Song")

    // ─── Judge-needed (numeric / hash filenames) ─────────────────────────

    @Test fun judgeNeededHash()   = assertInvalid("", "", "2_5375396427029582755.m4a")
    @Test fun judgeNeededNumeric()= assertInvalid("", "", "8.87.mp3")

    // ─── Emoji / URL / Telegram ──────────────────────────────────────────

    @Test fun emojiNote()         = cleanTitle("Song ♫", "Song")
    @Test fun emojiMusic()        = cleanTitle("Title 🎵", "Title")
    @Test fun urlHttp()           = cleanTitle("http://foo.com/Song", "Song")
    @Test fun telegramLink()      = cleanTitle("t.me/channel Song", "Song")
}
