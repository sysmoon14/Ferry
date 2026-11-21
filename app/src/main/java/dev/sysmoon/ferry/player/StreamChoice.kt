package dev.sysmoon.ferry.player

import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.StreamQuality

/**
 * How a channel's streams are ordered for playback and fallback: the stream the viewer pinned
 * first, then streams that have been failing moved down, keeping the channel's own streams ahead
 * of backups borrowed from other rows (other ITV 1 regions on 103).
 */
object StreamChoice {
    /** Failures older than this no longer count against a stream. */
    const val FAILURE_WINDOW_MS = 48 * 3_600_000L
    /** Failures kept per stream; more than this changes nothing. */
    const val MAX_FAILURES = 5

    /** Option for "let Ferry choose" in Home Assistant's stream select. */
    const val AUTOMATIC = "Automatic"

    fun order(
        own: List<ChannelEntity>,
        backups: List<ChannelEntity>,
        pinnedId: String?,
        failures: Map<String, List<Long>>,
        now: Long,
    ): List<ChannelEntity> {
        fun recent(s: ChannelEntity) = failures[s.id].orEmpty().count { now - it < FAILURE_WINDOW_MS }
        // A stable sort: streams with no recent failures keep the best-first order.
        val ranked = own.sortedBy(::recent) + backups.sortedBy(::recent)
        val pinned = ranked.firstOrNull { it.id == pinnedId } ?: return ranked
        return listOf(pinned) + (ranked - pinned)
    }

    /** A failure of [streamId] at [now], keeping only recent ones, at most one a minute. */
    fun recordFailure(failures: List<Long>, now: Long): List<Long> {
        val recent = failures.filter { now - it < FAILURE_WINDOW_MS }
        if (recent.any { now - it < 60_000 }) return recent
        return (recent + now).takeLast(MAX_FAILURES)
    }

    /**
     * One label per stream for Home Assistant's select, unique even when the playlist names several
     * streams the same ("BBC One · 1080p", "BBC One · 1080p (2)").
     */
    fun labels(streams: List<ChannelEntity>): List<String> {
        val seen = HashMap<String, Int>()
        return streams.map { s ->
            val base = listOfNotNull(StreamQuality.displayName(s.name), StreamQuality.parse(s.name).label).joinToString(" · ")
            val n = seen.merge(base, 1, Int::plus)!!
            if (n == 1) base else "$base ($n)"
        }
    }
}
