package ir.mariaplayer.data

data class Collection(
    val id: String,
    val label: String,
    val rule: CollectionRule,
    val isSystem: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * اگر true، ترک‌های این collection از collection های دیگر مخفی می‌شوند.
     * (Partition semantics — به‌زودی در resolve سراسری پیاده می‌شود.)
     */
    val excludeFromOthers: Boolean = false,
)

/**
 * یک «گزاره» (predicate) روی Track.
 * ساختار بازگشتی: هر rule می‌تواند از rule های دیگر ساخته شود
 * (And / Or / Not)، و اتم‌ها بر اساس metadata ترک تصمیم می‌گیرند.
 */
sealed interface CollectionRule {
    fun matches(track: Track): Boolean

    /** همیشه true — مجموعه‌ی جهانی ترک‌ها. */
    object All : CollectionRule {
        override fun matches(track: Track) = true
    }

    /** لیست دستی id ها. */
    data class Manual(val trackIds: List<Long>) : CollectionRule {
        override fun matches(track: Track) = track.id in trackIds
    }

    /** دامنه‌ی مدت (ms). null = بی‌نهایت. */
    data class Duration(val minMs: Long? = null, val maxMs: Long? = null) : CollectionRule {
        override fun matches(track: Track) =
            (minMs == null || track.durationMs >= minMs) &&
            (maxMs == null || track.durationMs <= maxMs)
    }

    /** regex روی فیلدهای متنی. */
    data class Pattern(
        val pattern: String,
        val fields: Set<Field> = Field.entries.toSet(),
    ) : CollectionRule {
        enum class Field { Title, Artist, Album, Folder, Filename }

        override fun matches(track: Track): Boolean {
            val re = try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                return false
            }
            return fields.any { f ->
                val v = when (f) {
                    Field.Title -> track.title
                    Field.Artist -> track.artist ?: ""
                    Field.Album -> track.album ?: ""
                    Field.Folder -> track.folder
                    Field.Filename -> track.filename
                }
                re.containsMatchIn(v)
            }
        }
    }

    // ───────── ترکیب‌ها (combinators) ─────────

    /** همه‌ی زیر-قاعده‌ها باید true باشند. خالی = true (vacuous truth). */
    data class And(val rules: List<CollectionRule>) : CollectionRule {
        override fun matches(track: Track) = rules.all { it.matches(track) }
    }

    /** حداقل یکی از زیر-قاعده‌ها true. خالی = false. */
    data class Or(val rules: List<CollectionRule>) : CollectionRule {
        override fun matches(track: Track) = rules.any { it.matches(track) }
    }

    /** نقیض. */
    data class Not(val rule: CollectionRule) : CollectionRule {
        override fun matches(track: Track) = !rule.matches(track)
    }
}
