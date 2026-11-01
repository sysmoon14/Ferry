package dev.sysmoon.ferry.data

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A stream that might show a match, with why Ferry thinks so. */
data class StreamCandidate(val channel: ChannelEntity, val score: Int, val reason: String) {
    val strength: String get() = when {
        score >= 85 -> "Best match"
        score >= 60 -> "Likely"
        else -> "Possible"
    }
}

/**
 * Finds streams in the playlist that are likely to show a match, strongest evidence first:
 *  1. the fixture in a channel's name (providers rename event channels on match day),
 *  2. both teams in a programme's title/description around kick-off (any country's guide),
 *  3. one team plus the competition in the guide, or a listed broadcaster,
 *  4. the club's own channel, or just the competition at kick-off on a sports channel.
 * Hidden groups are searched too: foreign sports channels are usually hidden.
 */
class MatchFinder(private val db: FerryDatabase) {

    /**
     * [sameSlot]: how many matches in this competition kick off at about the same time. A guide
     * that just says "Premier League" can't be pinned to one of several.
     */
    suspend fun find(f: Fixture, sameSlot: Int = 1): List<StreamCandidate> {
        val rows = db.channels().allPrimaries()
        val home = TeamNames.aliases(f.home)
        val away = TeamNames.aliases(f.away)
        val found = HashMap<String, StreamCandidate>()
        fun add(ch: ChannelEntity, score: Int, reason: String) {
            val boosted = score + bonus(ch)
            val prev = found[ch.id]
            if (prev == null || prev.score < boosted) found[ch.id] = StreamCandidate(ch, boosted, reason)
        }

        // 1. Channel names. Event channels usually carry a date ("31/10"); one for another day is
        // a different match (or an old one the provider never renamed).
        val koDate = java.time.Instant.ofEpochMilli(f.kickoff).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val koTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(f.kickoff))
        for (ch in rows) {
            if (TeamNames.otherSport(ch.name)) continue
            val text = TeamNames.normalise(ch.name)
            val h = TeamNames.mentions(text, home)
            val a = TeamNames.mentions(text, away)
            if (!h && !a) continue
            val dated = TeamNames.dateIn(ch.name, koDate.year)
            if (dated != null && dated != koDate) continue
            val group = TeamNames.normalise(ch.groupTitle.orEmpty())
            val versus = TeamNames.hasVersus(text)
            val eventish = group.contains("event") || group.contains("ppv") || versus
            when {
                h && a -> add(ch, 100, "Channel name: ${ch.name}")
                // One of our teams against someone else ("Man City vs Arsenal") is another match.
                versus -> Unit
                eventish && dated == koDate -> add(ch, 66, "Event channel: ${ch.name}")
                !eventish && (group.contains("team") || group.contains("club")) -> add(ch, 58, "Club channel: ${ch.name}")
            }
        }

        // 2-4. Guide data around kick-off, for every channel with a guide (hidden ones included)
        val byKey = rows.groupBy { it.epgKey }
        val programmes = db.programmes().spanning(f.kickoff + 20 * 60_000L, f.kickoff + 30 * 60_000L)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        var competitionOnly = 0
        for (p in programmes) {
            val channels = byKey[p.channelKey] ?: continue
            val title = TeamNames.normalise(p.title)
            val text = "$title ${TeamNames.normalise(p.description.orEmpty())}"
            val h = TeamNames.mentions(text, home)
            val a = TeamNames.mentions(text, away)
            val comp = f.competition.keywords.any { text.contains(" $it ") }
            // "Premier League Preview Show", "UCL Highlights"… aren't the match.
            val show = TeamNames.isMagazineShow(title)
            // Same competition name, different game: darts' "Premier League", youth and women's leagues.
            if (TeamNames.otherSport(p.title) || TeamNames.notFirstTeam(text)) continue
            val label = "Guide: ${p.title} (${time.format(Date(p.start))})"
            // A listing for this match starts around kick-off and lasts about a match, not a
            // four-hour "Premier League" block.
            val matchShaped = p.start in (f.kickoff - 45 * 60_000L)..(f.kickoff + 15 * 60_000L) &&
                p.stop - p.start <= 3 * 3_600_000L + 30 * 60_000L
            // One of our teams against someone else is a different match (or a replay).
            val otherMatch = (h xor a) && TeamNames.hasVersus(text)
            for (ch in channels) when {
                h && a -> add(ch, 90, label)
                (h || a) && comp && !show && !otherMatch && matchShaped -> add(ch, 72, label)
                comp && !show && !h && !a && matchShaped && sameSlot <= 1 && TeamNames.isSportsChannel(ch) && competitionOnly < 25 ->
                    { competitionOnly++; add(ch, 42, label) }
            }
        }

        // 5. Channels dedicated to the competition ("SuperSport Premier League", "Hub Premier 1",
        // "V Sport Premier League", "Optus Sport"): rights holders that show one match at a time,
        // so when this is the only game on they're almost certainly showing it, even with no guide.
        for (ch in rows) {
            if (ch.id in found) continue
            val name = TeamNames.normalise(ch.name)
            if (TeamNames.otherSport(ch.name) || TeamNames.notFirstTeam(name)) continue
            if (f.competition.channelWords.none { name.contains(" $it ") }) continue
            if (name.contains(" premier sports ")) continue // a broadcaster, not the league
            if (TeamNames.isReplay(ch)) continue
            // Numbered feeds ("Optus Sport 1…12", "Hub Premier 1…11"): with one game on, it's on the
            // first one or two; the rest are spare.
            val n = TeamNames.feedNumber(name)
            if (n != null && n > 2) continue
            val base = if (n == 2) 52 else 64
            if (sameSlot <= 1) add(ch, base, "${f.competition.label} channel (only game on)")
            else add(ch, base - 26, "${f.competition.label} channel ($sameSlot games at this time)")
        }

        // Broadcasters ESPN lists (mostly US: NBC, Peacock, USA Network, CBS, Paramount+…)
        for (b in f.broadcasts) {
            val key = TeamNames.normalise(b).trim()
            if (key.length < 3) continue
            // The network itself ("USA Network", "NBC HD", "Peacock 1"), not everything with its name in.
            rows.filter { TeamNames.isNetwork(it.name, key) }.take(4).forEach { add(it, 55, "Listed broadcaster: $b") }
            // Streaming services as numbered event channels ("Peacock | Event 1"): the match is on
            // one of the first few.
            rows.filter { TeamNames.isEventChannelOf(it.name, key) }
                .sortedBy { TeamNames.feedNumber(TeamNames.normalise(it.name)) ?: 99 }.take(3)
                .forEach { add(it, 46, "Listed broadcaster: $b (event channel)") }
        }

        return found.values.sortedWith(compareByDescending<StreamCandidate> { it.score }.thenByDescending { it.channel.quality })
            .take(60)
    }

    /** UK streams first, then better quality. */
    private fun bonus(ch: ChannelEntity): Int {
        var b = 0
        if (ch.tvgId?.lowercase()?.endsWith(".uk") == true || ch.groupTitle?.startsWith("UK", ignoreCase = true) == true) b += 6
        if (ch.hidden) b -= 1
        return b + (ch.quality / 1000).coerceIn(0, 3)
    }
}

object TeamNames {
    /** Extra names guides use for clubs (keys: normalised ESPN display names). */
    private val EXTRA = mapOf(
        "manchester united" to listOf("man utd", "man united", "manchester utd", "man u"),
        "manchester city" to listOf("man city", "manchester c"),
        "tottenham hotspur" to listOf("tottenham", "spurs"),
        "wolverhampton wanderers" to listOf("wolves", "wolverhampton"),
        "brighton hove albion" to listOf("brighton"),
        "nottingham forest" to listOf("nottm forest", "nott m forest", "notts forest"),
        "newcastle united" to listOf("newcastle"),
        "west ham united" to listOf("west ham"),
        "afc bournemouth" to listOf("bournemouth"),
        "leeds united" to listOf("leeds"),
        "crystal palace" to listOf("palace"),
        "sheffield united" to listOf("sheff utd", "sheffield utd"),
        "bayern munich" to listOf("bayern", "bayern munchen", "fc bayern"),
        "internazionale" to listOf("inter", "inter milan", "inter milano"),
        "inter milan" to listOf("inter", "internazionale"),
        "ac milan" to listOf("milan"),
        "paris saint germain" to listOf("psg", "paris sg", "paris st germain", "paris"),
        "atletico madrid" to listOf("atletico", "atletico de madrid", "atleti"),
        "barcelona" to listOf("barca", "fc barcelona"),
        "real madrid" to listOf("real madrid cf"),
        "borussia dortmund" to listOf("dortmund", "bvb"),
        "bayer leverkusen" to listOf("leverkusen", "bayer 04"),
        "rb leipzig" to listOf("leipzig"),
        "eintracht frankfurt" to listOf("frankfurt"),
        "sporting cp" to listOf("sporting", "sporting lisbon", "sporting clube"),
        "psv eindhoven" to listOf("psv"),
        "club brugge" to listOf("brugge", "club bruges"),
        "olympique de marseille" to listOf("marseille", "om"),
        "marseille" to listOf("olympique de marseille"),
        "napoli" to listOf("ssc napoli"),
        "juventus" to listOf("juve"),
        "atalanta" to listOf("atalanta bc"),
        "benfica" to listOf("sl benfica"),
        "fc porto" to listOf("porto"),
        "galatasaray" to listOf("galatasaray sk"),
        "celtic" to listOf("celtic fc"),
        "ajax amsterdam" to listOf("ajax"),
        "slavia prague" to listOf("slavia praha"),
        "fc copenhagen" to listOf("copenhagen", "kobenhavn", "fc kobenhavn"),
        "bodo glimt" to listOf("bodo glimt", "bodoe glimt"),
    )
    private val DROP = setOf("fc", "afc", "cf", "sc", "the")
    /** Too generic to stand alone (would match unrelated programmes). */
    private val AMBIGUOUS = setOf("city", "united", "real", "inter", "paris", "om", "milan", "forest", "palace", "sporting")

    fun normalise(s: String): String {
        val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace("ø", "o").replace("æ", "ae").replace("ß", "ss").replace("&", " ")
        return " " + plain.replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    }

    fun aliases(t: Team): Set<String> {
        val base = listOf(t.name, t.short).map { normalise(it).trim() }
        val set = LinkedHashSet<String>()
        for (b in base) {
            set += b
            set += b.split(' ').filterNot { it in DROP }.joinToString(" ")
            EXTRA[b]?.let { set += it }
        }
        return set.filter { it.length >= 4 || it == "psg" || it == "bvb" || it == "psv" }.toSet()
    }

    /** Whole-word match; generic one-word names ("Inter", "City") only count as part of longer names elsewhere. */
    fun mentions(normalisedText: String, aliases: Set<String>): Boolean =
        aliases.any { a -> a !in AMBIGUOUS && normalisedText.contains(" $a ") }

    private val SPORT_WORDS = listOf("sport", "football", "soccer", "futbol", "fussball", "calcio", "bein", "dazn", "espn",
        "tnt", "sky sports", "canal", "eleven", "supersport", "arena", "premier", "laliga", "movistar", "ziggo", "viaplay",
        "nova sport", "polsat", "cosmote", "ssc", "astro", "optus", "stan", "fox", "tsn", "sportsnet", "usa network",
        "peacock", "paramount", "cbs", "nbc", "telemundo", "univision", "tudn", "rmc", "eurosport", "setanta", "match")

    private val OTHER_SPORTS = Regex("""\b(rugby|super league|rhinos|nrl|nfl|nba|nhl|mlb|cricket|darts|pdc|snooker|tennis|golf|boxing|ufc|wwe|f1|motogp|nascar|hockey|basketball|baseball|netball|kabaddi)\b""")
    fun otherSport(name: String) = OTHER_SPORTS.containsMatchIn(normalise(name))

    private val NOT_FIRST_TEAM = Regex("""\b(u17|u18|u19|u21|u23|under 17|under 18|under 19|under 21|under 23|women|womens|wsl|premier league 2|pl2|youth|academy|futsal|legends|esports|efootball|fifa)\b""")
    /** Youth, women's, legends or e-sports versions of a competition aren't the first-team match. */
    fun notFirstTeam(normalisedText: String) = NOT_FIRST_TEAM.containsMatchIn(normalisedText)

    /** "X v Y", "X vs Y", "X - Y" in normalised text (the dash becomes a double space-free gap, so check the raw-ish forms). */
    fun hasVersus(normalisedText: String) =
        normalisedText.contains(" vs ") || normalisedText.contains(" v ") || normalisedText.contains(" versus ") || normalisedText.contains(" x ")

    private val SHOW_WORDS = Regex("""\b(preview|review|highlights|show|news|classic|classics|rewind|magazine|talk|tonight|extra|goals|gameday|matchday live|reaction|countdown|build up)\b""")
    fun isMagazineShow(normalisedTitle: String) = SHOW_WORDS.containsMatchIn(normalisedTitle)

    private val REPLAY = Regex("""\b(replay|replays|vod|24 7|highlights|classic|classics|rewind)\b""")
    /** Replay / VOD / 24-7 loops: never the live match. */
    fun isReplay(ch: ChannelEntity) = REPLAY.containsMatchIn(normalise(ch.name + " " + ch.groupTitle.orEmpty()))

    /** The feed number in "optus sport 8 fhd", "hub premier 10", "peacock event 3". */
    fun feedNumber(normalisedName: String): Int? =
        Regex(""" (\d{1,2}) (?:uhd |fhd |hd |sd |4k |1080p |720p |50fps )*$""").find(normalisedName)?.groupValues?.get(1)?.toIntOrNull()

    /** "Peacock | Event 3" / "Peacock Event 3" for broadcaster [key]. */
    fun isEventChannelOf(name: String, key: String): Boolean {
        val n = normalise(name)
        return n.contains(" $key ") && n.contains(" event ") && feedNumber(n) != null
    }

    /** A date like "31/10" or "31.10" in a channel name. */
    fun dateIn(name: String, year: Int): java.time.LocalDate? {
        val m = Regex("""(?<!\d)(\d{1,2})[/.](\d{1,2})(?![\d:])""").find(name) ?: return null
        return runCatching { java.time.LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
    }

    private val NETWORK_SUFFIX = setOf("hd", "fhd", "uhd", "4k", "us", "usa", "east", "west", "sports", "network", "1", "2", "3", "4")
    /** [name] is the broadcaster [key] itself, maybe with a quality/region suffix. */
    fun isNetwork(name: String, key: String): Boolean {
        val n = normalise(name.substringAfterLast('|')).trim()
        if (!n.startsWith(key)) return false
        val rest = n.removePrefix(key).trim().split(' ').filter { it.isNotEmpty() }
        return rest.size <= 2 && rest.all { it in NETWORK_SUFFIX }
    }

    fun isSportsChannel(ch: ChannelEntity, lenient: Boolean = false): Boolean {
        val text = normalise(ch.name + " " + ch.groupTitle.orEmpty())
        return lenient || SPORT_WORDS.any { text.contains(it) }
    }
}
