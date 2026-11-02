package dev.sysmoon.ferry.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Competition(
    val espn: String, val label: String, val short: String, val keywords: List<String>,
    /** Words in a channel's own name that make it a channel dedicated to this competition
     *  ("SuperSport Premier League", "Hub Premier 1", "V Sport Premier League"). */
    val channelWords: List<String>,
    /** On unless the user turns it off on the web page. */
    val default: Boolean = false,
) {
    PREMIER_LEAGUE("eng.1", "Premier League", "PL", listOf("premier league", "english premier", "epl"),
        listOf("premier league", "premier football", "hub premier", "optus sport", "epl"), default = true),
    CHAMPIONS_LEAGUE("uefa.champions", "Champions League", "UCL", listOf(
        "champions league", "uefa champions", "ucl", "liga de campeones", "ligue des champions",
        "liga dos campeoes", "champions league uefa", "lega dei campioni",
    ), listOf("champions league", "uefa champions"), default = true),
    EUROPA_LEAGUE("uefa.europa", "Europa League", "UEL", listOf("europa league", "uefa europa", "uel"), listOf("europa league")),
    CONFERENCE_LEAGUE("uefa.europa.conf", "Conference League", "UECL", listOf("conference league", "uefa conference", "uecl"), listOf("conference league")),
    FA_CUP("eng.fa", "FA Cup", "FAC", listOf("fa cup", "emirates fa cup"), emptyList()),
    EFL_CUP("eng.league_cup", "EFL Cup", "EFL", listOf("efl cup", "carabao cup", "league cup"), emptyList()),
    CHAMPIONSHIP("eng.2", "Championship", "EFL", listOf("championship", "efl championship", "sky bet championship"), emptyList()),
    SCOTTISH_PREM("sco.1", "Scottish Premiership", "SPFL", listOf("scottish premiership", "spfl", "scottish premier"), emptyList()),
    LA_LIGA("esp.1", "La Liga", "LIGA", listOf("la liga", "laliga", "liga ea sports", "primera division"), listOf("laliga", "la liga")),
    BUNDESLIGA("ger.1", "Bundesliga", "BUND", listOf("bundesliga"), listOf("bundesliga")),
    SERIE_A("ita.1", "Serie A", "SA", listOf("serie a"), listOf("serie a")),
    LIGUE_1("fra.1", "Ligue 1", "L1", listOf("ligue 1", "ligue1"), listOf("ligue 1")),
    WC_QUALIFIERS("fifa.worldq.uefa", "World Cup qualifiers", "WCQ", listOf("world cup qualifier", "world cup qualifying", "wc qualifier", "fifa world cup qualif"), emptyList()),
    NATIONS_LEAGUE("uefa.nations", "Nations League", "UNL", listOf("nations league", "uefa nations"), emptyList()),
    ;

    /** Cups and internationals have no "league" word of their own: a guide saying just "football" won't pin them. */
    val international: Boolean get() = this == WC_QUALIFIERS || this == NATIONS_LEAGUE

    companion object {
        val DEFAULT: Set<String> = entries.filter { it.default }.map { it.name }.toSet()
        fun enabled(names: Set<String>): List<Competition> = entries.filter { it.name in names }.ifEmpty { entries.filter { it.default } }
    }
}

data class Team(val id: String, val name: String, val short: String, val abbr: String, val logo: String?) {
    /** Stable key across competitions (ESPN team ids are global). */
    val key: String get() = id
}

enum class MatchState { UPCOMING, LIVE, FINISHED }

data class Fixture(
    val id: String,
    val competition: Competition,
    val kickoff: Long,
    val home: Team,
    val away: Team,
    val state: MatchState,
    /** "45'", "HT", "FT", or the kick-off detail. */
    val detail: String,
    val homeScore: String?,
    val awayScore: String?,
    /** Broadcasters ESPN lists (mostly US networks). */
    val broadcasts: List<String>,
) {
    val title: String get() = "${home.short} v ${away.short}"
}

/**
 * Fixtures and clubs from ESPN's public site API (free, no key, unofficial). Cached on disk so the
 * screen opens instantly and works through brief outages.
 */
class FootballData(private val http: OkHttpClient, private val cacheDir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** ESPN only accepts single dates (ranges get HTTP 400), so days are fetched in parallel. */
    suspend fun fixtures(c: Competition, from: LocalDate, days: Int): List<Fixture> = coroutineScope {
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val today = today()
        val results = (0..days).map { i ->
            val date = from.plusDays(i.toLong())
            async(Dispatchers.IO) {
                runCatching {
                    // Today's scores change minute to minute; other days rarely.
                    val maxAge = if (!date.isBefore(today.minusDays(1)) && !date.isAfter(today)) 2 * 60_000L else 3 * 3_600_000L
                    val body = cached("football_${c.espn}_${date.format(fmt)}.json", maxAge) {
                        "$API/${c.espn}/scoreboard?dates=${date.format(fmt)}"
                    }
                    parseScoreboard(body, c)
                }
            }
        }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
    }

    suspend fun teams(c: Competition): List<Team> = withContext(Dispatchers.IO) {
        val body = cached("football_${c.espn}_teams.json", maxAgeMs = 3 * 86_400_000L) { "$API/${c.espn}/teams" }
        parseTeams(body)
    }

    private fun cached(name: String, maxAgeMs: Long, url: () -> String): String {
        val file = File(cacheDir, name)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs) return file.readText()
        return try {
            val text = http.newCall(Request.Builder().url(url()).build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.body!!.string()
            }
            file.writeText(text)
            text
        } catch (e: Exception) {
            if (file.exists()) file.readText() else throw e
        }
    }

    companion object {
        private const val API = "https://site.api.espn.com/apis/site/v2/sports/soccer"
        /** ESPN's scoreboard abbreviations → the channel names playlists use. */
        private val BROADCAST_NAMES = mapOf(
            "Tele" to "Telemundo", "NBCSN" to "NBC Sports", "USA Net" to "USA Network", "CBSSN" to "CBS Sports Network",
            "Para+" to "Paramount+", "ESPN+" to "ESPN Plus", "UNIMAS" to "UniMas", "TUDN" to "TUDN",
        )
        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
        private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        private fun team(o: JsonObject?): Team? {
            o ?: return null
            val id = o.str("id") ?: return null
            val name = o.str("displayName") ?: o.str("name") ?: return null
            val logo = o.str("logo") ?: o["logos"].arr()?.firstOrNull().obj()?.str("href")
            return Team(id, name, o.str("shortDisplayName") ?: name, o.str("abbreviation") ?: "", logo)
        }

        fun parseScoreboard(body: String, c: Competition): List<Fixture> {
            val root = json.parseToJsonElement(body).jsonObject
            return root["events"].arr().orEmpty().mapNotNull { e ->
                val ev = e.obj() ?: return@mapNotNull null
                val comp = ev["competitions"].arr()?.firstOrNull().obj() ?: return@mapNotNull null
                val sides = comp["competitors"].arr().orEmpty().mapNotNull { it.obj() }
                val home = sides.firstOrNull { it.str("homeAway") == "home" } ?: sides.getOrNull(0) ?: return@mapNotNull null
                val away = sides.firstOrNull { it.str("homeAway") == "away" } ?: sides.getOrNull(1) ?: return@mapNotNull null
                val status = (ev["status"].obj() ?: comp["status"].obj())
                val type = status?.get("type").obj()
                val state = when (type?.str("state")) {
                    "in" -> MatchState.LIVE
                    "post" -> MatchState.FINISHED
                    else -> MatchState.UPCOMING
                }
                val kickoff = runCatching { Instant.parse(normaliseIso(ev.str("date") ?: comp.str("date")!!)).toEpochMilli() }
                    .getOrNull() ?: return@mapNotNull null
                val broadcasts = buildList {
                    comp["broadcasts"].arr().orEmpty().forEach { b ->
                        b.obj()?.get("names").arr()?.forEach { n -> (n as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let(::add) }
                    }
                    comp["geoBroadcasts"].arr().orEmpty().forEach { b ->
                        b.obj()?.get("media").obj()?.let { m -> m.str("shortName")?.let(::add); m.str("name")?.let(::add) }
                    }
                }.flatMap { listOfNotNull(it, BROADCAST_NAMES[it]) }.distinct()
                Fixture(
                    id = ev.str("id") ?: return@mapNotNull null,
                    competition = c,
                    kickoff = kickoff,
                    home = team(home["team"].obj()) ?: return@mapNotNull null,
                    away = team(away["team"].obj()) ?: return@mapNotNull null,
                    state = state,
                    detail = type?.str("shortDetail") ?: type?.str("detail") ?: "",
                    homeScore = home.str("score").takeIf { state != MatchState.UPCOMING },
                    awayScore = away.str("score").takeIf { state != MatchState.UPCOMING },
                    broadcasts = broadcasts,
                )
            }
        }

        fun parseTeams(body: String): List<Team> {
            val root = json.parseToJsonElement(body).jsonObject
            val leagues = root["sports"].arr()?.firstOrNull().obj()?.get("leagues").arr()?.firstOrNull().obj()
            return leagues?.get("teams").arr().orEmpty().mapNotNull { team(it.obj()?.get("team").obj()) }
        }

        /** ESPN dates look like "2026-10-31T15:00Z" (no seconds), which Instant.parse rejects. */
        fun normaliseIso(s: String): String =
            if (Regex("""T\d{2}:\d{2}Z$""").containsMatchIn(s)) s.replace(Regex("""(T\d{2}:\d{2})Z$"""), "$1:00Z") else s

        fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
    }
}

/**
 * Which channels have actually shown a competition's matches before. A pick only counts once
 * the same channel has played for [WATCH_MS] from the finder: trying a stream and moving on
 * says nothing, ten minutes on it says it was the right match.
 */
class FootballPicks(
    private val settings: Settings,
    private val player: dev.sysmoon.ferry.player.PlayerController,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private var job: kotlinx.coroutines.Job? = null

    /** The user started [channelId] for [f] from the finder. */
    fun started(f: Fixture, channelId: String) {
        job?.cancel()
        job = scope.launch {
            kotlinx.coroutines.delay(WATCH_MS)
            val s = player.state.value
            val onIt = (s.channel?.id == channelId || s.stream?.id == channelId) && s.state == dev.sysmoon.ferry.player.PlaybackState.PLAYING
            if (!onIt || !f.isOnNow()) return@launch
            settings.recordFootballPick(f.competition.name, channelId)
            dev.sysmoon.ferry.Logs.i("Football", "Remembered $channelId for ${f.competition.label} (${f.title})")
        }
    }

    /** Channel ids that have shown this competition before, most recent first. */
    suspend fun picks(c: Competition): List<String> = settings.footballPicks()[c.name].orEmpty()
    suspend fun allPicks(): Set<String> = settings.footballPicks().values.flatten().toSet()

    private fun Fixture.isOnNow(): Boolean {
        val now = System.currentTimeMillis()
        return now in (kickoff - 15 * 60_000L)..(kickoff + 150 * 60_000L)
    }

    companion object { const val WATCH_MS = 10 * 60_000L }
}
