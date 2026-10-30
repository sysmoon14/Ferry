package dev.sysmoon.ferry.data

/**
 * Providers often list one channel several times: "Disney Jr. FHD (1080p)", "… HD (720p)",
 * "… SD (576p)", plus "backup" feeds. Ferry folds these into one guide row that plays the best
 * stream and keeps the rest as fallbacks.
 *
 * Quality is read from the channel name, the only signal a playlist gives before a stream is
 * opened: resolution first, then frame rate, with backup/alternative feeds ranked just below
 * the main feed at the same resolution.
 */
object StreamQuality {
    data class Info(val height: Int?, val fps: Int?, val backup: Boolean) {
        /** Higher is better. Unknown resolution sits between SD and HD; unknown fps counts as 25. */
        val score: Int get() = (height ?: UNKNOWN_HEIGHT) * 1000 + (fps ?: 25) * 10 - if (backup) 500 else 0

        /** Short label for menus, e.g. "1080p · 50fps", "4K", "720p · backup". Null if nothing known. */
        val label: String?
            get() = buildList {
                height?.let { add(if (it >= 2160) "4K" else "${it}p") }
                fps?.let { add("${it}fps") }
                if (backup) add("backup")
            }.joinToString(" · ").ifEmpty { null }
    }

    private const val UNKNOWN_HEIGHT = 600

    private val EXPLICIT_HEIGHT = Regex("""(?<![\d.])(2160|1440|1080|720|576|540|480|360)\s*[pi](?:\s*(\d{2,3}))?(?![a-z])""", RegexOption.IGNORE_CASE)
    private val FPS = Regex("""(?<!\d)(\d{2,3})\s*(?:fps|hz)(?![a-z])""", RegexOption.IGNORE_CASE)
    private val UHD = Regex("""(?<![a-z\d])(?:4k|uhd|2160)(?![a-z\d])|ᵁᴴᴰ|⁴ᴷ""", RegexOption.IGNORE_CASE)
    private val FHD = Regex("""(?<![a-z\d])(?:fhd|full\s*hd)(?![a-z\d])|ᶠᴴᴰ""", RegexOption.IGNORE_CASE)
    private val HD = Regex("""(?<![a-z\d])hd\+?(?![a-z\d])|ᴴᴰ""", RegexOption.IGNORE_CASE)
    private val SD = Regex("""(?<![a-z\d])sd(?![a-z\d])|ˢᴰ""", RegexOption.IGNORE_CASE)
    private val LQ = Regex("""(?<![a-z\d])lq(?![a-z\d])""", RegexOption.IGNORE_CASE)
    private val BACKUP = Regex(
        """(?<![a-z\d])(?:backup|back\s*up|bkp|bk|alt|alternat(?:e|ive)|reserve|b/u|bu|feed\s*\d)(?![a-z\d])""",
        RegexOption.IGNORE_CASE,
    )

    /** Words that describe the stream rather than the channel; stripped to find the channel name. */
    private val NOISE = Regex(
        """(?<![a-z\d])(?:(?:2160|1440|1080|720|576|540|480|360)\s*[pi](?:\s*\d{2,3})?|\d{2,3}\s*(?:fps|hz)|4k|uhd|fhd|full\s*hd|hd\+?|sd|lq|hq|""" +
            """hevc|h\.?26[45]|x26[45]|avc|raw|vip|backup|back\s*up|bkp|bk|alt|alternat(?:e|ive)|reserve|b/u|bu|feed\s*\d|multi\s*audio)(?![a-z\d])|ᵁᴴᴰ|⁴ᴷ|ᶠᴴᴰ|ᴴᴰ|ˢᴰ|ᴿᴬᵂ""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(name: String): Info {
        val explicit = EXPLICIT_HEIGHT.find(name)
        val height = explicit?.groupValues?.get(1)?.toInt()
            ?: when {
                UHD.containsMatchIn(name) -> 2160
                FHD.containsMatchIn(name) -> 1080
                HD.containsMatchIn(name) -> 720
                SD.containsMatchIn(name) -> 576
                LQ.containsMatchIn(name) -> 360
                else -> null
            }
        val fps = explicit?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt()
            ?: FPS.find(name)?.groupValues?.get(1)?.toInt()
        return Info(height, fps?.takeIf { it in 10..240 }, BACKUP.containsMatchIn(name))
    }

    /** "Disney Jr. FHD (1080p)" → "Disney Jr.": the name to show for a merged row. */
    fun displayName(name: String): String = NOISE.replace(name, " ")
        .replace(Regex("""[(\[{]\s*[|/,·-]*\s*[)\]}]"""), " ") // brackets left empty
        .replace(Regex("""\s*[|/,·-]+\s*$"""), "")             // dangling separators
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifEmpty { name.trim() }

    /** Comparison key: the display name without case or punctuation. "+1" channels stay distinct. */
    fun matchKey(name: String): String = displayName(name).lowercase().filter { it.isLetterOrDigit() || it == '+' }
}

object Duplicates {
    /**
     * Groups duplicates within one source and returns every channel with [ChannelEntity.primaryId],
     * [ChannelEntity.quality] and [ChannelEntity.streamCount] set. Channels match when they're in
     * the same group, share a guide ID, and have the same name once quality tags are stripped
     * (so regional variants or +1 channels that borrow an EPG id don't merge).
     *
     * The best stream becomes the primary. It takes the group's earliest list position, and the
     * user's favourite/order from whichever member carried them, so the row doesn't move when
     * a refresh picks a different best stream.
     */
    fun merge(channels: List<ChannelEntity>, enabled: Boolean): List<ChannelEntity> {
        val scored = channels.map { it.copy(quality = StreamQuality.parse(it.name).score, primaryId = null, streamCount = 1) }
        if (!enabled) return scored

        val groups = scored.groupBy { ch ->
            val tvg = ch.tvgId?.trim()?.takeIf { it.isNotEmpty() }
            if (tvg == null) ch.id else "${ch.groupTitle}\u0000${tvg.lowercase()}\u0000${StreamQuality.matchKey(ch.name)}"
        }
        return groups.values.flatMap { members ->
            if (members.size == 1) return@flatMap members
            val ranked = members.sortedWith(compareByDescending<ChannelEntity> { it.quality }.thenBy { it.position })
            val first = members.minBy { it.position }
            val primary = ranked.first().copy(
                position = first.position,
                number = first.number ?: ranked.first().number,
                favourite = members.any { it.favourite },
                sortOrder = members.minOf { it.sortOrder },
                hidden = members.all { it.hidden },
                streamCount = members.size,
            )
            listOf(primary) + ranked.drop(1).map {
                it.copy(primaryId = primary.id, favourite = false, sortOrder = Int.MAX_VALUE)
            }
        }
    }
}
