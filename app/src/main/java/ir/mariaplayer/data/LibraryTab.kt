package ir.mariaplayer.data

data class LibraryTab(
    val id: String,
    val kind: Kind,
    val label: String,
    val playlistId: Long? = null,
    val isDefault: Boolean = false,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
) {
    enum class Kind { ALL, MUSIC, PODCAST, AUDIOBOOK, PLAYLIST, FAVORITES, SMART }

    fun matches(t: Track, favorites: Set<Long>): Boolean = when (kind) {
        Kind.ALL        -> true
        Kind.MUSIC      -> t.contentType == ContentType.MUSIC
        Kind.PODCAST    -> t.contentType == ContentType.PODCAST
        Kind.AUDIOBOOK  -> t.contentType == ContentType.AUDIOBOOK
        Kind.PLAYLIST   -> true
        Kind.FAVORITES  -> t.id in favorites
        Kind.SMART      -> (minDurationMs?.let { t.durationMs >= it } ?: true) &&
                           (maxDurationMs?.let { t.durationMs <= it } ?: true)
    }

    companion object {
        const val FAVORITES_ID = "favorites"
        const val FAVORITES_LABEL = "Favorites"

        fun defaults(): List<LibraryTab> = listOf(
            LibraryTab("all", Kind.ALL, "All", null, true),
            LibraryTab(FAVORITES_ID, Kind.FAVORITES, FAVORITES_LABEL, null, true),
            LibraryTab("music", Kind.MUSIC, "Music", null, true),
            LibraryTab("podcasts", Kind.PODCAST, "Podcasts", null, true),
            LibraryTab("audiobooks", Kind.AUDIOBOOK, "Audiobooks", null, true),
        )

        fun favorites(): LibraryTab =
            LibraryTab(FAVORITES_ID, Kind.FAVORITES, FAVORITES_LABEL, null, true)

        fun smart(id: String, label: String,
                  minDurationMs: Long? = null, maxDurationMs: Long? = null): LibraryTab =
            LibraryTab(id, Kind.SMART, label, null, false, minDurationMs, maxDurationMs)
    }
}
