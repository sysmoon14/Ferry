package dev.sysmoon.ferry.player

import dev.sysmoon.ferry.data.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamChoiceTest {
    private fun s(id: String, name: String = id) = ChannelEntity(id, 1, name, null, null, null, "http://x/$id", 0, null)
    private val now = 1_000_000_000L

    @Test fun keepsBestFirstWithoutFailures() {
        val own = listOf(s("a"), s("b")); val backups = listOf(s("c"))
        assertEquals(listOf("a", "b", "c"), StreamChoice.order(own, backups, null, emptyMap(), now).map { it.id })
    }

    @Test fun failingStreamsMoveDownWithinTheirOwnPart() {
        val own = listOf(s("a"), s("b")); val backups = listOf(s("c"), s("d"))
        val failures = mapOf("a" to listOf(now - 1_000), "c" to listOf(now - 2_000, now - 90_000))
        assertEquals(listOf("b", "a", "d", "c"), StreamChoice.order(own, backups, null, failures, now).map { it.id })
    }

    @Test fun oldFailuresAreForgotten() {
        val own = listOf(s("a"), s("b"))
        val failures = mapOf("a" to listOf(now - StreamChoice.FAILURE_WINDOW_MS - 1))
        assertEquals(listOf("a", "b"), StreamChoice.order(own, emptyList(), null, failures, now).map { it.id })
    }

    @Test fun pinnedStreamPlaysFirstEvenFromBackups() {
        val own = listOf(s("a"), s("b")); val backups = listOf(s("c"))
        val failures = mapOf("c" to listOf(now - 1_000))
        assertEquals(listOf("c", "a", "b"), StreamChoice.order(own, backups, "c", failures, now).map { it.id })
        assertEquals(listOf("a", "b", "c"), StreamChoice.order(own, backups, "gone", emptyMap(), now).map { it.id })
    }

    @Test fun recordsAtMostOneFailureAMinute() {
        val once = StreamChoice.recordFailure(emptyList(), now)
        assertEquals(listOf(now), once)
        assertEquals(once, StreamChoice.recordFailure(once, now + 30_000))
        assertEquals(2, StreamChoice.recordFailure(once, now + 61_000).size)
        val many = (1..10).fold(emptyList<Long>()) { acc, i -> StreamChoice.recordFailure(acc, now + i * 61_000L) }
        assertEquals(StreamChoice.MAX_FAILURES, many.size)
    }

    @Test fun labelsAreUniqueAndReadable() {
        val labels = StreamChoice.labels(listOf(s("a", "BBC One FHD (1080p)"), s("b", "BBC One FHD (1080p)"), s("c", "BBC One North West")))
        assertEquals("BBC One · 1080p", labels[0])
        assertEquals("BBC One · 1080p (2)", labels[1])
        assertEquals("BBC One North West", labels[2])
    }
}
