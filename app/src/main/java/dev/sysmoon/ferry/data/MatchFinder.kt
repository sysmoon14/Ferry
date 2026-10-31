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

    suspend fun find(f: Fixture): List<StreamCandidate> {
        val rows = db.channels().allPrimaries()
        val home = TeamNames.aliases(f.home)
        val away = TeamNames.aliases(f.away)
        val found = HashMap<String, StreamCandidate>()
        fun add(ch: ChannelEntity, score: Int, reason: String) {
            val boosted = score + bonus(ch)
            val prev = found[ch.id]
            if (prev == null || prev.score < boosted) found[ch.id] = StreamCandidate(ch, boosted, reason)
        }

        // 1. Channel names
        for (ch in rows) {
            val text = TeamNames.normalise(ch.name)
            val h = TeamNames.mentions(text, home)
            val a = TeamNames.mentions(text, away)
            val group = TeamNames.normalise(ch.groupTitle.orEmpty())
            when {
                h && a -> add(ch, 100, "Channel name: ${ch.name}")
                (h || a) && (group.contains("event") || group.contains("ppv") || text.contains(" vs ") || text.contains(" v ")) ->
                    add(ch, 62, "Event channel: ${ch.name}")
                (h || a) && (group.contains("team") || group.contains("club")) -> add(ch, 58, "Club channel: ${ch.name}")
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
            val label = "Guide: ${p.title} (${time.format(Date(p.start))})"
            for (ch in channels) when {
                h && a -> add(ch, 90, label)
                (h || a) && comp -> add(ch, 72, label)
                comp && TeamNames.isSportsChannel(ch) && competitionOnly < 25 -> { competitionOnly++; add(ch, 42, label) }
            }
        }

        // Broadcasters ESPN lists (mostly US: NBC, Peacock, USA Network, CBS, Paramount+…)
        for (b in f.broadcasts) {
            val key = TeamNames.normalise(b).trim()
            if (key.length < 3) continue
            rows.filter { TeamNames.normalise(it.name).trim().startsWith(key) && TeamNames.isSportsChannel(it, lenient = true) }
                .take(4).forEach { add(it, 55, "Listed broadcaster: $b") }
        }

        return found.values.sortedWith(compareByDescending<StreamCandidate> { it.score }.thenByDescending { it.channel.quality })
            .take(40)
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

    fun isSportsChannel(ch: ChannelEntity, lenient: Boolean = false): Boolean {
        val text = normalise(ch.name + " " + ch.groupTitle.orEmpty())
        return lenient || SPORT_WORDS.any { text.contains(it) }
    }
}
