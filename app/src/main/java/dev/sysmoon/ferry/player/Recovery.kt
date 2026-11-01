package dev.sysmoon.ferry.player

/**
 * What to do when a live stream fails, drops, stalls or freezes. Pure logic with no player in
 * it, so the rules are easy to read and to test:
 *
 *  1. If the stream had been playing, restart the same stream (keeps quality and timing, like
 *     flicking away and back) — at most [MAX_SAME_RESTARTS] times a minute.
 *  2. Otherwise move to the channel's next stream that hasn't been tried since it last played
 *     steadily (wrapping round).
 *  3. When every stream has been tried, retry from the best one after a pause — giving up after
 *     [MAX_ROUNDS] rounds in a minute.
 *
 * The backstop (nothing moved for a minute, whatever state the player is in) retunes the same
 * stream first, then the next one, at most [MAX_BACKSTOPS] times in ten minutes.
 */
class Recovery(private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface Action {
        /** Tune the current stream again. */
        data object RestartSame : Action
        /** Tune stream [index] (already made current). */
        data class Switch(val index: Int) : Action
        /** Every stream failed: pause briefly, then start again from the best one. */
        data object RetryRound : Action
        data object GiveUp : Action
    }

    var streamCount: Int = 1
        private set
    var index: Int = 0
        private set
    private val tried = mutableSetOf<Int>()
    private val sameRestarts = ArrayDeque<Long>()
    private val rounds = ArrayDeque<Long>()
    private val backstops = ArrayDeque<Long>()

    /** A channel was tuned: [count] streams, starting from [startIndex]. */
    fun tuned(count: Int, startIndex: Int = 0) {
        streamCount = count.coerceAtLeast(1)
        index = startIndex.coerceIn(0, streamCount - 1)
        tried.clear(); tried += index
        sameRestarts.clear()
        rounds.clear()
    }

    /** The current stream has played steadily: a later failure gets a fresh round of backups. */
    fun stable() {
        tried.clear(); tried += index
    }

    /** The current stream failed; [hadStarted] if it was playing before it did. */
    fun onFailure(hadStarted: Boolean): Action {
        val now = clock()
        if (hadStarted) {
            prune(sameRestarts, now, 60_000)
            if (sameRestarts.size < MAX_SAME_RESTARTS) {
                sameRestarts.addLast(now)
                return Action.RestartSame
            }
        }
        nextUntried()?.let { next ->
            index = next; tried += next
            return Action.Switch(next)
        }
        prune(rounds, now, 60_000)
        rounds.addLast(now)
        if (rounds.size > MAX_ROUNDS) return Action.GiveUp
        index = 0
        tried.clear(); tried += 0
        return Action.RetryRound
    }

    /** Nothing has moved for a minute: retune (same stream first, then the next); null = too many already. */
    fun onBackstop(): Action? {
        val now = clock()
        prune(backstops, now, 10 * 60_000)
        if (backstops.size >= MAX_BACKSTOPS) return null
        backstops.addLast(now)
        if (backstops.size > 1 && streamCount > 1) {
            index = (index + 1) % streamCount
            tried.clear(); tried += index
            return Action.Switch(index)
        }
        return Action.RestartSame
    }

    private fun nextUntried(): Int? =
        (1 until streamCount).map { (index + it) % streamCount }.firstOrNull { it !in tried }

    private fun prune(q: ArrayDeque<Long>, now: Long, windowMs: Long) {
        while (q.isNotEmpty() && q.first() < now - windowMs) q.removeFirst()
    }

    companion object {
        const val MAX_SAME_RESTARTS = 3
        const val MAX_ROUNDS = 3
        const val MAX_BACKSTOPS = 4
    }
}
