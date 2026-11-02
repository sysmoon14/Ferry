package dev.sysmoon.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballTest {
    private val scoreboard = """
    {"events":[{"id":"740001","date":"2026-10-31T15:00Z","name":"Chelsea at Liverpool",
      "status":{"type":{"state":"pre","detail":"Sat, October 31st at 3:00 PM GMT","shortDetail":"10/31 - 3:00 PM"}},
      "competitions":[{"date":"2026-10-31T15:00Z",
        "competitors":[
          {"homeAway":"home","score":"0","team":{"id":"364","displayName":"Liverpool","shortDisplayName":"Liverpool","abbreviation":"LIV","logo":"https://a.espncdn.com/i/teamlogos/soccer/500/364.png"}},
          {"homeAway":"away","score":"0","team":{"id":"363","displayName":"Chelsea","shortDisplayName":"Chelsea","abbreviation":"CHE","logo":"https://x/363.png"}}],
        "broadcasts":[{"market":"national","names":["USA Net","Peacock"]}],
        "geoBroadcasts":[{"media":{"shortName":"USA Net"}}]}]},
     {"id":"740002","date":"2026-10-31T12:30Z","status":{"type":{"state":"in","shortDetail":"67'"}},
      "competitions":[{"competitors":[
          {"homeAway":"home","score":"2","team":{"id":"360","displayName":"Manchester United","shortDisplayName":"Man United","abbreviation":"MAN"}},
          {"homeAway":"away","score":"1","team":{"id":"359","displayName":"Arsenal","shortDisplayName":"Arsenal","abbreviation":"ARS"}}]}]}]}
    """.trimIndent()

    @Test fun parsesScoreboard() {
        val list = FootballData.parseScoreboard(scoreboard, Competition.PREMIER_LEAGUE)
        assertEquals(2, list.size)
        val f = list[0]
        assertEquals("Liverpool", f.home.name)
        assertEquals("Chelsea", f.away.name)
        assertEquals(MatchState.UPCOMING, f.state)
        assertEquals(java.time.Instant.parse("2026-10-31T15:00:00Z").toEpochMilli(), f.kickoff)
        assertEquals(listOf("USA Net", "USA Network", "Peacock"), f.broadcasts)
        assertEquals(null, f.homeScore)
        val live = list[1]
        assertEquals(MatchState.LIVE, live.state)
        assertEquals("2", live.homeScore)
        assertEquals("67'", live.detail)
    }

    @Test fun parsesTeams() {
        val body = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"364","displayName":"Liverpool","shortDisplayName":"Liverpool","abbreviation":"LIV","logos":[{"href":"https://x/364.png"}]}}]}]}]}"""
        val teams = FootballData.parseTeams(body)
        assertEquals(1, teams.size)
        assertEquals("https://x/364.png", teams[0].logo)
    }

    private fun team(name: String, short: String = name) = Team("1", name, short, "", null)

    @Test fun matchesTeamNamesInGuideText() {
        val liv = TeamNames.aliases(team("Liverpool"))
        val che = TeamNames.aliases(team("Chelsea"))
        val text = TeamNames.normalise("Premier League: Liverpool v Chelsea")
        assertTrue(TeamNames.mentions(text, liv))
        assertTrue(TeamNames.mentions(text, che))
        // Foreign-language titles and accents
        assertTrue(TeamNames.mentions(TeamNames.normalise("Fútbol: Liverpool - Chelsea (Premier League)"), liv))
        val bayern = TeamNames.aliases(team("Bayern Munich"))
        assertTrue(TeamNames.mentions(TeamNames.normalise("Bayern München – PSG"), bayern))
        // Common short forms
        val united = TeamNames.aliases(team("Manchester United", "Man United"))
        assertTrue(TeamNames.mentions(TeamNames.normalise("EPL 03: Man Utd vs Arsenal 12:30"), united))
        // Whole words only: "Liverpool" mustn't match inside other words, and generic names don't count
        assertFalse(TeamNames.mentions(TeamNames.normalise("Liverpoolfc.tv highlights"), liv))
        val city = TeamNames.aliases(team("Manchester City", "Man City"))
        assertFalse(TeamNames.mentions(TeamNames.normalise("City of London documentary"), city))
    }

    @Test fun filtersLookalikes() {
        assertTrue(TeamNames.otherSport("Super League Plus | Castlford Tigers v Leeds Rhinos"))
        assertFalse(TeamNames.otherSport("EPL02: Bournemouth 15:00 Leeds 31/10"))
        assertEquals(java.time.LocalDate.of(2026, 10, 31), TeamNames.dateIn("EPL02: Bournemouth 15:00 Leeds 31/10", 2026))
        assertEquals(java.time.LocalDate.of(2026, 4, 25), TeamNames.dateIn("FA Cup | Chelsea vs Leeds 25/04 15:00", 2026))
        assertEquals(null, TeamNames.dateIn("Leeds United", 2026))
        assertTrue(TeamNames.isNetwork("USA Network HD", TeamNames.normalise("USA Network").trim()))
        assertTrue(TeamNames.isNetwork("US | Peacock 1", "peacock"))
        assertFalse(TeamNames.isNetwork("Peacock Live | The Bob Ross Channel", "peacock"))
        assertTrue(TeamNames.isMagazineShow(TeamNames.normalise("The Premier League Preview Show")))
        assertFalse(TeamNames.isMagazineShow(TeamNames.normalise("Live: Premier League")))
    }

    @Test fun versusDetection() {
        assertTrue(TeamNames.hasVersus(TeamNames.normalise("Preseason | Man City vs Arsenal 15:00")))
        assertTrue(TeamNames.hasVersus(TeamNames.normalise("Premier League: Liverpool v Chelsea")))
        assertFalse(TeamNames.hasVersus(TeamNames.normalise("EPL Events 03: Liverpool")))
    }

    // Seen on 1 Nov 2026: "Premier League" darts and an under-18 game offered for Liverpool v Arsenal.
    @Test fun sameNameDifferentGame() {
        assertTrue(TeamNames.otherSport("PDC ANZ Premier League Darts - Week 6 (Finals)"))
        assertTrue(TeamNames.notFirstTeam(TeamNames.normalise("Under 18 Premier League")))
        assertTrue(TeamNames.notFirstTeam(TeamNames.normalise("WSL: Arsenal Women v Chelsea Women")))
        assertTrue(TeamNames.notFirstTeam(TeamNames.normalise("Premier League 2: Liverpool U21")))
        assertFalse(TeamNames.notFirstTeam(TeamNames.normalise("Live Super Sunday: Liverpool v Arsenal")))
        assertFalse(TeamNames.otherSport("SuperSport Premier League"))
    }

    @Test fun feedsAndEventChannels() {
        assertEquals(8, TeamNames.feedNumber(TeamNames.normalise("Optus Sport 8 FHD")))
        assertEquals(10, TeamNames.feedNumber(TeamNames.normalise("Hub Premier 10 FHD")))
        assertEquals(null, TeamNames.feedNumber(TeamNames.normalise("SuperSport Premier League")))
        assertTrue(TeamNames.isEventChannelOf("Peacock | Event 3", "peacock"))
        assertFalse(TeamNames.isEventChannelOf("Peacock | Event 3", "nbc"))
    }

    // Seen on 2 Nov 2026 for Newcastle v Everton: other "Premier Leagues", a Polish magazine
    // show, other clubs' own channels, and Paramount Network mistaken for Paramount+.
    @Test fun lookalikesSeenInNovember() {
        assertTrue(TeamNames.otherLeague(TeamNames.normalise("Canadian Premier League Soccer")))
        assertTrue(TeamNames.otherLeague(TeamNames.normalise("Scottish Premiership: Celtic v Rangers")))
        assertFalse(TeamNames.otherLeague(TeamNames.normalise("Scottish Premiership: Celtic v Rangers"), Competition.SCOTTISH_PREM))
        assertFalse(TeamNames.otherLeague(TeamNames.normalise("Live Premier League: Newcastle v Everton")))
        assertTrue(TeamNames.isMagazineShow(TeamNames.normalise("Jej Wysokość Premier League")))
        val ch = { name: String, group: String -> ChannelEntity(id = name, sourceId = 1, name = name, tvgId = null, logo = null, groupTitle = group, streamUrl = "http://x", position = 0, number = null) }
        val newcastle = TeamNames.aliases(team("Newcastle United", "Newcastle"))
        val everton = TeamNames.aliases(team("Everton"))
        assertTrue(TeamNames.isOtherClubChannel(ch("MUTV FHD (1080p)", "UK | Sports*"), newcastle, everton))
        assertTrue(TeamNames.isOtherClubChannel(ch("LFC FHD (1080p)", "UK | Sports*"), newcastle, everton))
        assertFalse(TeamNames.isOtherClubChannel(ch("Everton", "⚽️ | EPL Teams"), newcastle, everton))
        assertFalse(TeamNames.isOtherClubChannel(ch("Sky Sports Main Event UHD", "UK | Sports*"), newcastle, everton))
        assertTrue(TeamNames.isMultiView(TeamNames.normalise("EPL Goal Rush: Multi View")))
        val paramount = TeamNames.normalise("Paramount+").trim()
        assertEquals("paramount plus", paramount)
        assertFalse(TeamNames.isNetwork("Paramount Network FHD", paramount))
        assertTrue(TeamNames.isEventChannelOf("US | Paramount+ Event 01", paramount))
    }
}
