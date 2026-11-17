package ir.mariaplayer.data

data class Collection(
    val id: String,
    val label: String,
    val rule: CollectionRule,
    val isSystem: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

sealed interface CollectionRule {
    fun matches(track: Track): Boolean

    object All : CollectionRule {
        override fun matches(track: Track) = true
    }

    data class Manual(val trackIds: List<Long>) : CollectionRule {
        override fun matches(track: Track) = track.id in trackIds
    }

    data class Duration(val minMs: Long? = null, val maxMs: Long? = null) : CollectionRule {
        override fun matches(track: Track) =
            (minMs == null || track.durationMs >= minMs) &&
            (maxMs == null || track.durationMs <= maxMs)
    }

    data class Types(val types: Set<ContentType>) : CollectionRule {
        override fun matches(track: Track) = track.contentType in types
    }

    data class Pattern(
        val pattern: String,
        val fields: Set<Field> = Field.entries.toSet(),
    ) : CollectionRule {
        enum class Field { Title, Album, Folder, Filename }
        override fun matches(track: Track): Boolean {
            val re = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { return false }
            return fields.any { f ->
                val v = when (f) {
                    Field.Title -> track.title
                    Field.Album -> track.album ?: ""
                    Field.Folder -> track.folder
                    Field.Filename -> track.filename
                }
                re.containsMatchIn(v)
            }
        }
    }
}
