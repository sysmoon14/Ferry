package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyMatcherTest {
    private fun sky(n: Int, t: String, g: Int = 3) = SkyLineup.SkyChannel(n, t, g)
    private fun ch(id: String, name: String, tvg: String?, group: String = "UK | Entertainment ∞*", pos: Int = 0, q: Int = 0) =
        SkyMatcher.Candidate(id, name, tvg, group, q, pos)

    @Test fun canonicalisesSkyAbbreviations() {
        assertEquals(SkyMatcher.canon("Sky Sports Main Event"), SkyMatcher.canon("SkySpMainEvHD"))
        assertEquals(SkyMatcher.canon("Sky Sports Premier League"), SkyMatcher.canon("SkySp PL HD"))
        assertEquals(SkyMatcher.canon("Sky Sports Football"), SkyMatcher.canon("SkySp F'ball HD"))
        assertEquals(SkyMatcher.canon("Comedy Central +1 SD (576p)"), SkyMatcher.canon("ComedyCent+1"))
        assertEquals(SkyMatcher.canon("Cartoon Network +1 SD (576p)"), SkyMatcher.canon("CN+1"))
        assertEquals(SkyMatcher.canon("BBC One North West FHD (1080p)"), SkyMatcher.canon("BBC One NW HD"))
        assertEquals(SkyMatcher.canon("Channel 5"), SkyMatcher.canon("5 HD"))
        assertEquals(SkyMatcher.canon("Channel 5 +1 SD (576p)"), SkyMatcher.canon("5+1"))
        assertEquals("5usa", SkyMatcher.canon("5 USA"))
        assertEquals(SkyMatcher.canon("Dave"), SkyMatcher.canon("U&Dave"))
        assertEquals(SkyMatcher.canon("TNT Sports 1"), SkyMatcher.canon("TNTSports1 HD"))
        assertEquals(SkyMatcher.canon("Sky Showcase"), SkyMatcher.canon("Sky One"))
        assertEquals(SkyMatcher.canon("Comedy Central"), SkyMatcher.canon("ComedyCentral"))
        assertEquals(SkyMatcher.canon("True Crime Xtra SD (576p)"), SkyMatcher.canon("TRUE CRIME X"))
        assertEquals(SkyMatcher.canon("TNT Sports Box Office"), SkyMatcher.canon("TNTSBoxOffHD"))
        assertEquals(SkyMatcher.canon("BBC One North East and Cumbria FHD (1080p)"), SkyMatcher.canon("BBC One NE HD"))
        assertEquals("comedycentralplus1", SkyMatcher.tvgStem("comedycentralplus1.uk"))
    }

    @Test fun matchesALineup() {
        val lineup = listOf(
            sky(101, "BBC One NW HD"), sky(103, "ITV1 HD"), sky(105, "5 HD"), sky(106, "Sky One"), sky(111, "U&Dave"),
            sky(203, "ITV1+1"), sky(301, "SkyPremiereHD", 6), sky(401, "SkySpMainEvHD", 7), sky(410, "TNTSports1 HD", 7),
            sky(423, "LFCTV HD", 7), sky(601, "Cartoon Netwrk", 2), sky(640, "Cartoon Net HD", 2), sky(806, "Sky One HD"), sky(999, "Nothing Like It"),
        )
        val playlist = listOf(
            ch("bbc1nw", "BBC One North West FHD (1080p)", "bbcnorthwest.uk", "UK | Regionals*"),
            ch("itvgranada", "ITV 1 Granada FHD (1080p)", "ITV1GRH", "UK | Regionals*"),
            ch("itvlondon", "ITV 1 London FHD (1080p)", "itvlondon.uk", "UK | Regionals*"),
            ch("itvgranada1", "ITV 1 Granada +1 SD (576p)", null, "UK | Regionals*"),
            ch("five", "Channel 5", "channel5.uk"),
            ch("showcase", "Sky Showcase", "skyshowcase.uk"),
            ch("dave", "Dave", "dave.uk"),
            ch("chdave", "CH: Dave", null, "Switzerland Locals"),
            ch("premiere", "Sky Cinema Premiere", "skypremiere.uk", "UK | Movies*"),
            ch("mainevent", "Sky Sports Main Event", "skysportsmainevent.uk", "UK | Sports*"),
            ch("tnt1", "TNT Sports 1", "btsport1.uk", "UK | Sports*"),
            ch("lfc", "LFC", "liverpoolfctv.uk", "UK | Sports*"),
            ch("cn", "Cartoon Network", "cartoonnetwork.uk", "UK | Kids*"),
            ch("cnus", "Cartoon Network FHD", "CartoonNetwork.us", "US | Kids"),
        )
        val result = SkyMatcher.match(lineup, playlist, region = 45)
        val byNumber = result.matches.associate { it.sky.number to it.channelId }
        assertEquals("bbc1nw", byNumber[101])
        assertEquals("itvgranada", byNumber[103])      // Merseyside → Granada, not London
        assertEquals("itvgranada1", byNumber[203])
        assertEquals("five", byNumber[105])
        assertEquals("showcase", byNumber[106])
        assertEquals("dave", byNumber[111])
        assertEquals("premiere", byNumber[301])
        assertEquals("mainevent", byNumber[401])
        assertEquals("tnt1", byNumber[410])
        assertEquals("cn", byNumber[601])
        assertEquals("lfc", byNumber[423])
        assertTrue(640 !in byNumber && 806 !in byNumber) // HD duplicates don't take a second number
        assertEquals(listOf(999), result.unmatched.map { it.number })
    }

    @Test fun seasonalRenameUsesOldName() {
        val result = SkyMatcher.match(
            listOf(sky(412, "SkySp NFL HD", 7)),
            listOf(ch("action", "Sky Sports Action", "skysportsaction.uk", "UK | Sports*")),
            region = 45,
        )
        assertEquals("action", result.matches.single().channelId)
    }

    @Test fun matchesRenamedChannelsBySchedule() {
        val h = 3_600_000L
        val t0 = 1_800_000_000_000L
        val nfl = SkyLineup.SkyChannel(412, "SkySp Pop-up HD", 7, "sid412")
        val bbcYl = SkyLineup.SkyChannel(955, "BBC One Y&L HD", 3, "sid955")
        val sky = mapOf(
            "sid412" to listOf(
                SkyLineup.SkyEvent(t0, "Live NFL: Bears @ Packers"), SkyLineup.SkyEvent(t0 + 3 * h, "NFL Review"),
                SkyLineup.SkyEvent(t0 + 4 * h, "Inside the NFL"), SkyLineup.SkyEvent(t0 + 5 * h, "NFL Films Presents"),
            ),
            "sid955" to listOf(
                SkyLineup.SkyEvent(t0, "Breakfast"), SkyLineup.SkyEvent(t0 + 3 * h, "Morning Live"),
                SkyLineup.SkyEvent(t0 + 4 * h, "Homes Under the Hammer"),
            ),
        )
        val guide = listOf(
            // The playlist still calls it "Sky Sports Action"; titles differ slightly from Sky's.
            SkyMatcher.GuideEntry("action", t0 + 60_000, "NFL: Bears v Packers"),
            SkyMatcher.GuideEntry("action", t0 + 3 * h, "NFL Review"),
            SkyMatcher.GuideEntry("action", t0 + 4 * h, "Inside The NFL"),
            SkyMatcher.GuideEntry("action", t0 + 5 * h, "NFL Films Presents"),
            SkyMatcher.GuideEntry("golf", t0, "Live PGA Tour Golf"),
            // Two BBC One regions with identical schedules: ambiguous, so no match.
            SkyMatcher.GuideEntry("bbcEM", t0, "Breakfast"), SkyMatcher.GuideEntry("bbcEM", t0 + 3 * h, "Morning Live"),
            SkyMatcher.GuideEntry("bbcEM", t0 + 4 * h, "Homes Under the Hammer"),
            SkyMatcher.GuideEntry("bbcWM", t0, "Breakfast"), SkyMatcher.GuideEntry("bbcWM", t0 + 3 * h, "Morning Live"),
            SkyMatcher.GuideEntry("bbcWM", t0 + 4 * h, "Homes Under the Hammer"),
        )
        // A channel repeating one title all day is never matched on schedule alone.
        val loop = SkyLineup.SkyChannel(700, "Loop TV", 3, "sid700")
        val loopSky = (0..5).map { SkyLineup.SkyEvent(t0 + it * h, "Paid Programming") }
        val loopGuide = (0..5).map { SkyMatcher.GuideEntry("other", t0 + it * h, "Paid Programming") }
        val matches = SkyMatcher.matchBySchedule(listOf(nfl, bbcYl, loop), sky + ("sid700" to loopSky), guide + loopGuide, t0 - h, t0 + 10 * h)
        assertEquals(listOf(412 to "action"), matches.map { it.sky.number to it.channelId })
    }

    @Test fun streamKeySurvivesRenames() {
        assertEquals("2:x713338", streamKey(2, "https://p.example/live/u/p/713338.ts"))
        assertEquals("1:https://x/a.m3u8", streamKey(1, "https://x/a.m3u8?token=abc"))
    }

}
