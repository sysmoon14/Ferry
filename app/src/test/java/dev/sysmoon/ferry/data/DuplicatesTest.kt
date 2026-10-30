package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    @Test fun parsesQualityFromNames() {
        StreamQuality.parse("Disney Jr. FHD (1080p)").let { assertEquals(1080, it.height); assertNull(it.fps) }
        assertEquals(720, StreamQuality.parse("Disney Jr. HD (720p)").height)
        assertEquals(576, StreamQuality.parse("Disney Jr. SD (576p)").height)
        assertEquals(2160, StreamQuality.parse("Sky Sports Main Event UHD").height)
        assertEquals(2160, StreamQuality.parse("UK: BBC One 4K").height)
        assertEquals(1080, StreamQuality.parse("ITV1 FHD").height)
        assertEquals(720, StreamQuality.parse("ITV1 HD").height)
        assertEquals(720, StreamQuality.parse("Channel 4 ᴴᴰ").height)
        StreamQuality.parse("Sky Sports F1 1080p50").let { assertEquals(1080, it.height); assertEquals(50, it.fps) }
        StreamQuality.parse("Sky Sports F1 FHD 50fps").let { assertEquals(1080, it.height); assertEquals(50, it.fps) }
        assertTrue(StreamQuality.parse("Sky News HD Backup").backup)
        assertTrue(StreamQuality.parse("Sky News (ALT)").backup)
        assertNull(StreamQuality.parse("BBC Alba").height)
        assertEquals(false, StreamQuality.parse("BBC Alba").backup)
        assertEquals(false, StreamQuality.parse("Sky Sports 2").backup)
    }

    @Test fun ranksResolutionThenFpsThenMainFeed() {
        fun score(n: String) = StreamQuality.parse(n).score
        assertTrue(score("X FHD") > score("X HD"))
        assertTrue(score("X HD") > score("X SD"))
        assertTrue(score("X 1080p 50fps") > score("X 1080p"))
        assertTrue(score("X FHD") > score("X FHD backup"))
        assertTrue(score("X FHD backup") > score("X HD"))
        assertTrue(score("X UHD") > score("X 1080p 60fps"))
    }

    @Test fun displayNameStripsQualityTags() {
        assertEquals("Disney Jr.", StreamQuality.displayName("Disney Jr. FHD (1080p)"))
        assertEquals("Sky Sports F1", StreamQuality.displayName("Sky Sports F1 1080p50"))
        assertEquals("UK | ITV1", StreamQuality.displayName("UK | ITV1 HD"))
        assertEquals("BBC Alba", StreamQuality.displayName("BBC Alba"))
        assertEquals("Sky Sports Main Event", StreamQuality.displayName("Sky Sports Main Event FHD (5.1)"))
        assertEquals("Sky Sports Main Event", StreamQuality.displayName("Sky Sports Main Event HVEC HB (1080p)"))
        assertTrue(StreamQuality.parse("X HVEC HB (1080p)").score > StreamQuality.parse("X HVEC LB (1080p)").score)
        assertEquals(StreamQuality.matchKey("Channel 4 HD"), StreamQuality.matchKey("Channel 4 SD"))
        assertTrue(StreamQuality.matchKey("Channel 4") != StreamQuality.matchKey("Channel 4 +1"))
    }

    private fun ch(id: String, name: String, tvg: String?, pos: Int, group: String = "UK", fav: Boolean = false) =
        ChannelEntity(id, 1, name, tvg, null, group, "http://x/$id.ts", pos, null, favourite = fav)

    @Test fun mergesByGuideIdAndNameWithinGroup() {
        val merged = Duplicates.merge(
            listOf(
                ch("a", "Disney Jr. SD (576p)", "disneyjr.uk", 0, fav = true),
                ch("b", "Disney Jr. FHD (1080p)", "disneyjr.uk", 1),
                ch("c", "Disney Jr. HD (720p)", "disneyjr.uk", 2),
                ch("d", "Channel 4 +1", "channel4.uk", 3),   // borrows Channel 4's EPG id
                ch("e", "Channel 4 HD", "channel4.uk", 4),
                ch("f", "No EPG HD", null, 5),
                ch("g", "No EPG SD", null, 6),
                ch("h", "Disney Jr. HEVC HB (1080p)", "disneyjr.uk", 7, group = "UK HEVC"), // other group
                ch("i", "Disney Jr. UHD", "disneyjr.uk", 8, group = "UK 4K").copy(hidden = true), // hidden group
            ),
            enabled = true,
        ).associateBy { it.id }

        val primary = merged.getValue("b")
        assertNull(primary.primaryId)
        assertEquals(4, primary.streamCount)
        assertEquals("UK", primary.groupTitle)
        assertEquals(0, primary.position)        // takes the group's first slot
        assertTrue(primary.favourite)            // keeps the favourite from the SD copy
        assertEquals("b", merged.getValue("a").primaryId)
        assertEquals("b", merged.getValue("c").primaryId)
        assertEquals(false, merged.getValue("a").favourite)

        assertNull(merged.getValue("d").primaryId)
        assertNull(merged.getValue("e").primaryId)
        assertNull(merged.getValue("f").primaryId)
        assertNull(merged.getValue("g").primaryId)
        assertEquals("b", merged.getValue("h").primaryId) // same channel from the HEVC group
        assertNull(merged.getValue("i").primaryId)         // hidden 4K copy is never used
        assertEquals(1, merged.getValue("i").streamCount)
    }

    @Test fun disabledLeavesEveryChannelStandalone() {
        val merged = Duplicates.merge(
            listOf(ch("a", "X SD", "x", 0), ch("b", "X HD", "x", 1)),
            enabled = false,
        )
        assertTrue(merged.all { it.primaryId == null && it.streamCount == 1 })
    }
}
