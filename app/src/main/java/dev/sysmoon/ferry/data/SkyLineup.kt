package dev.sysmoon.ferry.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Sky UK's channel line-up (numbers and genre sections) from the public feed behind sky.com's
 * TV guide, used to lay Ferry out like a Sky box: matched channels take their Sky number and sit
 * in Sky's sections in Sky order. Nothing is streamed from Sky; this is only the channel list.
 */
class SkyLineup(private val http: OkHttpClient, private val cacheDir: File) {
    @Serializable data class SkyChannel(val number: Int, val title: String, val genre: Int) {
        val section: String get() = SECTIONS[genre] ?: "Other"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The line-up for a Sky region, cached for a day. Falls back to the cache if Sky can't be reached. */
    fun channels(region: Int): List<SkyChannel> {
        val cache = File(cacheDir, "sky_lineup_$region.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 86_400_000L
        if (!fresh) {
            runCatching { fetch(region) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { raw ->
                cache.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SkyChannel.serializer()), raw))
                return raw
            }
        }
        if (!cache.exists()) error("Couldn't load the Sky line-up")
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SkyChannel.serializer()), cache.readText())
    }

    private fun fetch(region: Int): List<SkyChannel> {
        // 4096 is the full line-up (SD-box numbering, so HD copies sit at 8xx and are de-duplicated).
        for (bouquet in listOf(4096, 4097)) {
            val body = http.newCall(
                Request.Builder().url("$API/services/$bouquet/$region").header("X-SkyOTT-Territory", "GB").build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: continue
            val services = ((json.parseToJsonElement(body) as? JsonObject)?.get("services") as? JsonArray).orEmpty()
            val list = services.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val c = o.str("c") ?: return@mapNotNull null
                if (c.startsWith("0")) return@mapNotNull null // radio uses 0xxx
                val genre = o.str("sg")?.toIntOrNull() ?: return@mapNotNull null
                if (genre == 0 || genre == 4) return@mapNotNull null // line-up info, radio
                SkyChannel(c.toIntOrNull() ?: return@mapNotNull null, o.str("t") ?: return@mapNotNull null, genre)
            }
            if (list.isNotEmpty()) return list.sortedBy { it.number }
        }
        return emptyList()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    companion object {
        private const val API = "https://awk.epgsky.com/hawk/linear"
        const val DEFAULT_REGION = 45

        /** Sky's genre ids ("sg") → section names, in the order Sky's guide shows them. */
        val SECTIONS = linkedMapOf(3 to "Entertainment", 6 to "Movies", 7 to "Sports", 5 to "News", 2 to "Kids", 1 to "Shopping")

        /** Sky region numbers (the line-up's sub-bouquet) → names, from Sky's region mapping. */
        val REGIONS: Map<Int, String> = linkedMapOf(
            1 to "London", 2 to "Essex", 3 to "West Midlands", 4 to "HTV West", 5 to "Meridian South",
            6 to "Westcountry", 7 to "Granada", 8 to "West Yorkshire", 9 to "Thames Valley", 10 to "Meridian East",
            12 to "Border England", 13 to "Tyne", 18 to "London / Essex", 19 to "Atherstone", 20 to "East Midlands",
            21 to "Norfolk", 24 to "Gloucester", 25 to "West Anglia", 26 to "North Yorkshire", 27 to "Tring",
            28 to "South Lakeland", 29 to "Humber", 32 to "Wales", 33 to "Northern Ireland", 34 to "Channel Isles",
            35 to "Grampian", 36 to "Border Scotland", 37 to "Scottish West", 38 to "Scottish East", 39 to "Dundee",
            41 to "Ridge Hill", 45 to "Merseyside", 50 to "Republic of Ireland", 60 to "Sheffield", 61 to "Scarborough",
            62 to "North East Midlands", 64 to "London Kent", 65 to "Brighton", 67 to "West Dorset",
            68 to "Meridian North", 69 to "Tees", 71 to "Oxford", 72 to "South Yorkshire",
        )

        /** ITV1 franchise per region, so "ITV1 HD" finds the right regional feed in a playlist. */
        val ITV_REGION: Map<Int, String> = mapOf(
            1 to "london", 18 to "london", 64 to "london", 2 to "angliaeast", 21 to "angliaeast", 25 to "angliawest",
            3 to "centralwest", 19 to "centralwest", 41 to "centralwest", 20 to "centraleast", 62 to "centraleast",
            4 to "westcountry", 24 to "west", 5 to "meridiansouth", 67 to "meridiansouth", 10 to "meridianeast",
            65 to "meridianeast", 68 to "meridiannorth", 9 to "meridianthamesvalley", 71 to "meridianthamesvalley",
            6 to "westcountry", 7 to "granada", 45 to "granada", 28 to "border", 12 to "border", 13 to "tyneteesnorth",
            69 to "tyneteessouth", 8 to "yorkshirewest", 26 to "yorkshireeast", 29 to "yorkshireeast", 60 to "yorkshirewest",
            61 to "yorkshireeast", 72 to "yorkshirewest", 32 to "wales", 34 to "channeltv",
        )
    }
}

/**
 * Matches Sky channels to playlist channels. Sky's titles are squeezed into ~12 characters
 * ("SkySpMainEvHD", "ComedyCent+1", "BBC One NW HD"), so both sides are expanded into one
 * canonical key; playlist channels also match on their guide ID stem ("comedycentralplus1.uk").
 */
object SkyMatcher {
    data class Candidate(
        val id: String, val name: String, val tvgId: String?, val group: String?, val quality: Int, val position: Int,
    )
    data class Match(val channelId: String, val sky: SkyLineup.SkyChannel)
    data class Result(val matches: List<Match>, val unmatched: List<SkyLineup.SkyChannel>)

    private val EXPANSIONS = listOf(
        "skysp" to "sky sports ", "mainev" to "main event", "f'ball" to "football", "netwrk" to "network",
        "cartoon net " to "cartoon network ", "comedycent" to "comedy central", "bbc parl" to "bbc parliament",
        "disc." to "discovery ", "crime+inv" to "crime investigation", "tntsports" to "tnt sports ",
        "nickelodeonhd" to "nickelodeon", "bbcscotland" to "bbc scotland", "food netwrk" to "food network",
        "talkingpictures" to "talking pictures", "skypremiere" to "sky premiere", "skyanimation" to "sky animation",
        "g! mystery" to "great mystery", "great! xmas" to "great christmas", "great!christmas" to "great christmas",
        "great!" to "great ", "mstHveIdeas".lowercase() to "must have ideas", "boxoff" to " box office",
    )
    private val BBC_REGIONS = mapOf(
        "nw" to "north west", "ne" to "north east", "yks" to "yorkshire", "y&l" to "yorkshire lincolnshire",
        "wm" to "west midlands", "emid" to "east midlands", "east" to "east", "se" to "south east", "wst" to "west",
        "sth" to "south", "sw" to "south west", "ci" to "channel islands", "scot" to "scotland", "wal" to "wales",
        "ni" to "northern ireland", "lon" to "london",
    )

    /** Canonical comparison key: lower case, "+1" as "plus1", quality tags and punctuation removed. */
    fun canon(raw: String): String {
        var s = raw.lowercase().replace("ᴴᴰ", " ")
        // Drop a country prefix like "UK: " or "UK | ".
        s = s.replace(Regex("""^uk\s*[:|]\s*"""), "")
        // "SkySp PL HD" → "sky sports premier league"
        EXPANSIONS.forEach { (a, b) -> s = s.replace(a, b) }
        s = s.replace(Regex("""\bsky sports\s+pl\b"""), "sky sports premier league")
            .replace(Regex("""\bsky sports\s*\+(?!\s*1)"""), "sky sports plus")
            .replace(Regex("""\bcn\+1"""), "cartoon network+1")
        // "BBC One NW HD" → "bbc one north west"
        Regex("""^bbc (one|two) ([a-z&]+)\s*hd$""").find(s.trim())?.let { m ->
            BBC_REGIONS[m.groupValues[2]]?.let { s = "bbc ${m.groupValues[1]} $it" }
        }
        s = s.replace(Regex("""([a-eg-tv-z0-9])hd\b"""), "$1 hd") // "SkyPremiereHD" → "skypremiere hd" (not FHD/UHD)
            .replace(Regex("""\s*\+\s*1\b"""), " plus1")
            .replace(Regex("""^u\s*&\s*"""), "")                 // "U&Dave" → "dave"
            .replace(Regex("""^5(\s+hd)?\s*$"""), "channel 5")     // "5 HD" → "channel 5" (not "5USA")
            .replace(Regex("""^5\s*plus1\s*$"""), "channel 5 plus1")
            .replace(Regex("""\bsky one\b"""), "sky showcase")   // Sky's feed still says Sky One
            .replace(Regex("""\bnick jr\.?"""), "nick jr")
        return StreamQuality.matchKey(s).replace("+", "")
    }

    /** "comedycentralplus1.uk" → "comedycentralplus1"; null for ids that aren't UK-style stems. */
    fun tvgStem(tvgId: String?): String? {
        val t = tvgId?.trim()?.lowercase() ?: return null
        if (!t.endsWith(".uk")) return null
        return t.removeSuffix(".uk").filter { it.isLetterOrDigit() }.ifEmpty { null }
    }

    fun match(sky: List<SkyLineup.SkyChannel>, candidates: List<Candidate>, region: Int): Result {
        // Index every candidate under its name key and guide-ID stem.
        val index = HashMap<String, MutableList<Candidate>>()
        for (c in candidates) {
            // UK channels only: other countries' copies share names ("PL | Comedy Central").
            val uk = c.tvgId?.trim()?.lowercase()?.endsWith(".uk") == true || c.group?.startsWith("UK", ignoreCase = true) == true
            if (!uk) continue
            listOfNotNull(canon(c.name), tvgStem(c.tvgId)).distinct().forEach { k -> index.getOrPut(k) { mutableListOf() } += c }
        }
        fun best(list: List<Candidate>) = list.sortedWith(
            compareByDescending<Candidate> { it.tvgId?.lowercase()?.endsWith(".uk") == true }
                .thenByDescending { it.group?.startsWith("UK", ignoreCase = true) == true }
                .thenByDescending { it.quality }
                .thenBy { it.position }
        ).first()

        val itv = SkyLineup.ITV_REGION[region]
        val usedChannels = HashSet<String>()
        val usedKeys = HashSet<String>()
        val matches = mutableListOf<Match>()
        val unmatched = mutableListOf<SkyLineup.SkyChannel>()
        for (ch in sky.sortedBy { it.number }) {
            val key = canon(ch.title)
            if (!usedKeys.add(key)) continue // HD duplicate at 8xx of a channel already placed
            val keys = buildList {
                if (itv != null && key.startsWith("itv1") && !key.startsWith("itv1granada")) add("itv1$itv" + key.removePrefix("itv1"))
                add(key)
                if (ch.genre == 6 && key.startsWith("sky") && !key.startsWith("skycinema")) add("skycinema" + key.removePrefix("sky"))
                if (key.endsWith("hd")) add(key.removeSuffix("hd"))
            }
            val found = keys.firstNotNullOfOrNull { k -> index[k]?.filter { it.id !in usedChannels }?.takeIf { it.isNotEmpty() } }
            if (found == null) { unmatched += ch; continue }
            val c = best(found)
            usedChannels += c.id
            matches += Match(c.id, ch)
        }
        return Result(matches, unmatched)
    }
}
