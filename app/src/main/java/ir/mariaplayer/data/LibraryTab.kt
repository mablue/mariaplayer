package ir.mariaplayer.data

data class LibraryTab(
    val id: String,
    val kind: Kind,
    val label: String,
    val playlistId: Long? = null,
    val isDefault: Boolean = false,
) {
    enum class Kind { ALL, MUSIC, PODCAST, AUDIOBOOK, PLAYLIST }

    companion object {
        fun defaults(): List<LibraryTab> = listOf(
            LibraryTab("all", Kind.ALL, "All", null, true),
            LibraryTab("music", Kind.MUSIC, "Music", null, true),
            LibraryTab("podcasts", Kind.PODCAST, "Podcasts", null, true),
            LibraryTab("audiobooks", Kind.AUDIOBOOK, "Audiobooks", null, true),
        )
    }
}
