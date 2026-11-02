package dev.sysmoon.ferry.data

import dev.sysmoon.ferry.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A stream that might show a match, with why Ferry thinks so. */
data class StreamCandidate(val channel: ChannelEntity, val score: Int, val reason: String, val group: Group = Group.OTHER) {
    enum class Group(val label: String) { UK("Your UK channels"), OTHER("Other countries"), EVENT("Club & event channels") }
    val strength: String get() = when {
        score >= 85 -> "Best match"
        score >= 60 -> "Likely"
        else -> "Possible"
    }
}

/**
 * Finds streams in the playlist that are likely to show a match, strongest evidence first, in
 * passes that each emit the list so far (the quick ones show at once):
 *  1. channel names (providers rename event channels on match day), channels dedicated to the
 *     competition, and the broadcasters ESPN lists — all from the channel list alone;
 *  2. Sky's own 7-day guide for the UK sports channels the Sky layout matched: it names the
 *     match a week out, long before most providers' guides reach the day;
 *  3. the playlist guides around kick-off: both teams in a title/description (any country), or
 *     one team plus the competition.
 * Channels that showed the competition before (watched for ten minutes from this screen) are
 * boosted. Hidden groups are searched too: foreign sports channels are usually hidden.
 */
class MatchFinder(
    private val db: FerryDatabase,
    private val settings: Settings,
    private val sky: SkyLineup,
    private val picks: FootballPicks,
) {
    private data class Cached(val at: Long, val list: List<StreamCandidate>)
    private val cache = HashMap<String, Cached>()

    /** The last complete result for [f], if found in the last few minutes. */
    @Synchronized fun cached(f: Fixture): List<StreamCandidate>? =
        cache[f.id]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.list

    /**
     * [sameSlot]: how many matches in this competition kick off at about the same time. A guide
     * that just says "Premier League" can't be pinned to one of several.
     */
    fun find(f: Fixture, sameSlot: Int = 1): Flow<List<StreamCandidate>> = flow {
        cached(f)?.let { emit(it); return@flow }
        val t0 = System.currentTimeMillis()
        val rows = withContext(Dispatchers.IO) { db.channels().allPrimaries() }
        val home = TeamNames.aliases(f.home)
        val away = TeamNames.aliases(f.away)
        val ukPicks = picks.picks(f.competition).toSet()
        val anyPicks = picks.allPicks()
        val found = HashMap<String, StreamCandidate>()
        fun add(ch: ChannelEntity, score: Int, reason: String) {
            var boosted = score + bonus(ch)
            var why = reason
            when {
                ch.id in ukPicks -> { boosted += 12; why += " · showed ${f.competition.short} before" }
                ch.id in anyPicks -> { boosted += 5; why += " · showed football before" }
            }
            val prev = found[ch.id]
            if (prev == null || prev.score < boosted) found[ch.id] = StreamCandidate(ch, boosted, why, groupOf(ch, why))
        }
        fun snapshot() = found.values.sortedWith(compareByDescending<StreamCandidate> { it.score }.thenByDescending { it.channel.quality }).take(60)

        // ---- 1. The channel list alone (milliseconds).
        val koDate = java.time.Instant.ofEpochMilli(f.kickoff).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        for (ch in rows) {
            if (TeamNames.otherSport(ch.name)) continue
            val text = TeamNames.normalise(ch.name)
            val h = TeamNames.mentions(text, home)
            val a = TeamNames.mentions(text, away)
            if (!h && !a) continue
            // Event channels usually carry a date ("31/10"); one for another day is a different
            // match (or an old one the provider never renamed).
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
        // Channels dedicated to the competition ("SuperSport Premier League", "Hub Premier 1",
        // "V Sport Premier League", "Optus Sport"): rights holders that show one match at a time,
        // so when this is the only game on they're almost certainly showing it, even with no guide.
        for (ch in rows) {
            if (ch.id in found) continue
            val name = TeamNames.normalise(ch.name)
            if (TeamNames.otherSport(ch.name) || TeamNames.notFirstTeam(name) || TeamNames.otherLeague(name, f.competition)) continue
            if (f.competition.channelWords.none { name.contains(" $it ") }) continue
            if (name.contains(" premier sports ")) continue // a broadcaster, not the league
            if (TeamNames.isReplay(ch)) continue
            if (TeamNames.isMultiView(name)) { add(ch, 40, "${f.competition.label} multi-view"); continue }
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
        emit(snapshot())

        // ---- 2. Sky's guide for UK sports channels (a week ahead, with the match in the synopsis).
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        runCatching { skyPass(f, rows, home, away, time, ::add) }
            .onFailure { Logs.w(TAG, "Sky guide pass failed", it) }
        emit(snapshot())

        // ---- 3. The playlist guides around kick-off, for every channel with a guide (hidden ones included).
        val byKey = rows.groupBy { it.epgKey }
        val programmes = withContext(Dispatchers.IO) {
            db.programmes().spanning(f.kickoff + 20 * 60_000L, f.kickoff + 30 * 60_000L, f.kickoff + 6 * 3_600_000L)
        }
        var competitionOnly = 0
        for (p in programmes) {
            val channels = byKey[p.channelKey] ?: continue
            val title = TeamNames.normalise(p.title)
            val text = "$title ${TeamNames.normalise(p.description.orEmpty())}"
            val h = TeamNames.mentions(text, home)
            val a = TeamNames.mentions(text, away)
            // "Canadian Premier League", "Scottish Premiership" aren't this competition.
            val comp = f.competition.keywords.any { text.contains(" $it ") } && !TeamNames.otherLeague(text, f.competition)
            // "Premier League Preview Show", "UCL Highlights"… aren't the match.
            val show = TeamNames.isMagazineShow(title)
            // Same competition name, different game: darts' "Premier League", youth and women's leagues.
            if (TeamNames.otherSport(p.title) || TeamNames.notFirstTeam(text)) continue
            val label = "Guide: ${p.title} (${time.format(Date(p.start))})"
            // A listing for this match starts around kick-off and lasts about a match, not a
            // four-hour "Premier League" block. Up to 75 minutes early: many broadcasters' match
            // block includes the build-up (Viaplay NL: "Premier League" 15:30 for a 16:30 kick-off).
            val matchShaped = p.start in (f.kickoff - 75 * 60_000L)..(f.kickoff + 15 * 60_000L) &&
                p.stop - p.start <= 4 * 3_600_000L
            // One of our teams against someone else is a different match (or a replay).
            val otherMatch = (h xor a) && TeamNames.hasVersus(text)
            for (ch in channels) {
                // Another club's own channel (MUTV, LFC TV) shows its own club, whatever its guide says.
                if (!(h && a) && TeamNames.isOtherClubChannel(ch, home, away)) continue
                when {
                    h && a -> add(ch, 90, label)
                    (h || a) && comp && !show && !otherMatch && matchShaped -> add(ch, 72, label)
                    comp && !show && !h && !a && matchShaped && sameSlot <= 1 && TeamNames.isSportsChannel(ch) && competitionOnly < 25 ->
                        { competitionOnly++; add(ch, 42, label) }
                }
            }
        }
        val result = snapshot()
        synchronized(this) { cache[f.id] = Cached(System.currentTimeMillis(), result) }
        Logs.i(TAG, "Finder: ${result.size} streams for ${f.title} in ${System.currentTimeMillis() - t0} ms")
        emit(result)
    }

    /**
     * Sky's schedule for the day, for the sports channels the Sky layout matched (Sky Sports,
     * TNT Sports, Premier Sports…). Titles are "Live UEFA Champions League" with the teams in
     * the synopsis, or "Live MNF: Newcastle v Everton".
     */
    private suspend fun skyPass(
        f: Fixture, rows: List<ChannelEntity>, home: Set<String>, away: Set<String>,
        time: SimpleDateFormat, add: (ChannelEntity, Int, String) -> Unit,
    ) {
        if (!settings.skyLayout()) return
        val lineup = withContext(Dispatchers.IO) { sky.channels(settings.skyRegion()) }
        val sportsSids = lineup.filter { it.genre == 7 && it.sid.isNotBlank() }.associate { it.number to it.sid }
        val bySid = rows.filter { it.skyNumber != null }.groupBy { sportsSids[it.skyNumber] }.filterKeys { it != null }
        if (bySid.isEmpty()) return
        val day = java.time.Instant.ofEpochMilli(f.kickoff).atZone(java.time.ZoneId.of("Europe/London")).toLocalDate()
        val schedules = withContext(Dispatchers.IO) { sky.schedules(bySid.keys.filterNotNull(), day) }
        for ((sid, events) in schedules) {
            val channels = bySid[sid] ?: continue
            for (e in events) {
                val stop = if (e.durationMs > 0) e.start + e.durationMs else e.start + 2 * 3_600_000L
                // On air at kick-off + 20 min, having started no more than 90 min before kick-off.
                if (e.start > f.kickoff + 20 * 60_000L || stop < f.kickoff + 20 * 60_000L) continue
                if (e.start < f.kickoff - 90 * 60_000L) continue
                val text = TeamNames.normalise(e.title + " " + e.synopsis.orEmpty())
                if (TeamNames.otherSport(e.title) || TeamNames.notFirstTeam(text)) continue
                val h = TeamNames.mentions(text, home)
                val a = TeamNames.mentions(text, away)
                val comp = f.competition.keywords.any { text.contains(" $it ") } && !TeamNames.otherLeague(text, f.competition)
                val label = "Sky guide: ${e.title} (${time.format(Date(e.start))})"
                when {
                    h && a -> channels.forEach { add(it, 96, label) }
                    (h || a) && comp && !TeamNames.hasVersus(text) -> channels.forEach { add(it, 74, label) }
                }
            }
        }
    }

    private fun groupOf(ch: ChannelEntity, reason: String): StreamCandidate.Group = when {
        reason.startsWith("Event channel") || reason.startsWith("Club channel") || reason.contains("event channel") ||
            reason.contains("multi-view") -> StreamCandidate.Group.EVENT
        isUk(ch) -> StreamCandidate.Group.UK
        else -> StreamCandidate.Group.OTHER
    }

    private fun isUk(ch: ChannelEntity) =
        ch.tvgId?.lowercase()?.endsWith(".uk") == true || ch.groupTitle?.startsWith("UK", ignoreCase = true) == true || ch.skyNumber != null

    /** UK streams first, then better quality. */
    private fun bonus(ch: ChannelEntity): Int {
        var b = 0
        if (isUk(ch)) b += 6
        if (ch.hidden) b -= 1
        return b + (ch.quality / 1000).coerceIn(0, 3)
    }

    private companion object {
        const val TAG = "Football"
        const val CACHE_MS = 3 * 60_000L
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
            .replace("ø", "o").replace("æ", "ae").replace("ß", "ss").replace("&", " ").replace("+", " plus ")
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

    /** Other "Premier Leagues" and "premierships" that share a name with the English one. */
    private val OTHER_LEAGUE = Regex("""\b(canadian|scottish|irish|welsh|northern irish|indian|russian|ukrainian|hong kong|saudi|egyptian|nigerian|kenyan|ghana|south african|australian|malaysian|singapore|cypriot|israeli|belarus|kazakh|azerbaijan|armenian|georgian|icelandic|maltese) (premier|super|pro) ?(league|ship)?\b""")
    /** Another country's "Premier League"/"Premiership" — unless that's the competition being looked for. */
    fun otherLeague(normalisedText: String, c: Competition? = null): Boolean =
        OTHER_LEAGUE.findAll(normalisedText).any { m -> !(c == Competition.SCOTTISH_PREM && m.value.startsWith("scottish")) }

    private val MULTI_VIEW = Regex("""\b(multi view|multiview|goal rush|goal zone|goals show|golazo|red zone|redzone)\b""")
    fun isMultiView(normalisedName: String) = MULTI_VIEW.containsMatchIn(normalisedName)

    /** Club TV channels, which only ever show their own club. */
    private val CLUB_CHANNEL = Regex("""\b(mutv|lfc|lfctv|liverpool fc tv|chelsea tv|arsenal tv|spurs tv|tottenham tv|city tv|man city tv|rangers tv|celtic tv|barca tv|real madrid tv|psg tv|juventus tv|milan tv|inter tv|roma tv|lazio style|bayern tv|bvb tv|benfica tv|porto canal|sporting tv|olympique tv|ajax tv|villa tv|avtv|everton tv|newcastle tv|nufc tv|leeds tv|lutv|wolves tv|forest tv|palace tv|hammers tv|whu tv|saints tv|brighton tv|leicester tv|lcfc tv|fulham tv|brentford tv|bournemouth tv|afcb tv|sunderland tv|safc tv|ipswich tv|burnley tv)\b""")
    /** A club's own channel, and the club isn't in this match. */
    fun isOtherClubChannel(ch: ChannelEntity, home: Set<String>, away: Set<String>): Boolean {
        val n = normalise(ch.name)
        val club = CLUB_CHANNEL.containsMatchIn(n) || normalise(ch.groupTitle.orEmpty()).let { it.contains(" team") || it.contains(" club") }
        return club && !mentions(n, home) && !mentions(n, away)
    }

    private val NOT_FIRST_TEAM = Regex("""\b(u17|u18|u19|u21|u23|under 17|under 18|under 19|under 21|under 23|women|womens|wsl|premier league 2|pl2|youth|academy|futsal|legends|esports|efootball|fifa)\b""")
    /** Youth, women's, legends or e-sports versions of a competition aren't the first-team match. */
    fun notFirstTeam(normalisedText: String) = NOT_FIRST_TEAM.containsMatchIn(normalisedText)

    /** "X v Y", "X vs Y", "X - Y" in normalised text (the dash becomes a double space-free gap, so check the raw-ish forms). */
    fun hasVersus(normalisedText: String) =
        normalisedText.contains(" vs ") || normalisedText.contains(" v ") || normalisedText.contains(" versus ") || normalisedText.contains(" x ")

    private val SHOW_WORDS = Regex("""\b(preview|review|highlights|show|news|classic|classics|rewind|magazine|magazyn|talk|tonight|extra|goals|gameday|matchday live|reaction|countdown|build up|jej wysokosc|studio|resumen|resumo|zusammenfassung|le mag|podsumowanie|skrot|skroty|analisis|debate|el chiringuito)\b""")
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
