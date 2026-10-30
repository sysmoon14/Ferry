package dev.sysmoon.ferry.data

import android.util.Log
import dev.sysmoon.ferry.parse.M3uEntry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * BBC iPlayer live channels as an opt-in source. Unofficial: uses the same public
 * Media Selector request that Streamlink's bbciplayer plugin makes (BSD-2-Clause,
 * see NOTICE.md). No DRM is involved and nothing is decrypted; the BBC still applies
 * its UK-only availability, and a TV Licence is required to watch.
 *
 * Channels are stored with a `bbc:` pseudo-URL listing candidate service IDs
 * (HD first); the real stream URL is resolved each time a channel is played.
 */
@OptIn(ExperimentalSerializationApi::class)
class BbcIplayer(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class BbcChannel(
        val key: String,
        val name: String,
        /** Media Selector service IDs to try, best first. */
        val services: List<String>,
        /** iBL channel id used for schedules. */
        val scheduleId: String,
    )

    sealed interface Resolved {
        data class Ok(val url: String, val service: String) : Resolved
        data class Failed(val reason: String) : Resolved
    }

    /** National line-up. BBC One is added separately for the chosen region. */
    private val national = listOf(
        BbcChannel("bbc_two", "BBC Two", listOf("bbc_two_hd", "bbc_two_england"), "bbc_two_england"),
        BbcChannel("bbc_three", "BBC Three", listOf("bbc_three_hd", "bbc_three"), "bbc_three"),
        BbcChannel("bbc_four", "BBC Four", listOf("bbc_four_hd", "bbc_four"), "bbc_four"),
        BbcChannel("bbc_news", "BBC News", listOf("bbc_news24"), "bbc_news24"),
        BbcChannel("bbc_parliament", "BBC Parliament", listOf("bbc_parliament"), "bbc_parliament"),
        BbcChannel("cbbc", "CBBC", listOf("cbbc_hd", "cbbc"), "cbbc"),
        BbcChannel("cbeebies", "CBeebies", listOf("cbeebies_hd", "cbeebies"), "cbeebies"),
        BbcChannel("bbc_scotland", "BBC Scotland", listOf("bbc_scotland_hd", "bbc_scotland"), "bbc_scotland"),
        BbcChannel("bbc_alba", "BBC Alba", listOf("bbc_alba"), "bbc_alba"),
        BbcChannel("s4c", "S4C", listOf("s4cpbs"), "s4cpbs"),
    )

    fun lineup(region: String): List<BbcChannel> {
        val r = REGIONS[region] ?: REGIONS.getValue(DEFAULT_REGION)
        val one = BbcChannel(
            key = "bbc_one",
            name = "BBC One ${r.label}",
            services = listOf(region, "bbc_one_hd").distinct(),
            scheduleId = region,
        )
        return listOf(one) + national
    }

    fun entries(region: String): List<M3uEntry> = lineup(region).mapIndexed { i, ch ->
        M3uEntry(
            name = ch.name,
            url = SCHEME + ch.services.joinToString(","),
            tvgId = EPG_PREFIX + ch.key,
            tvgName = null,
            logo = null,
            group = GROUP,
            number = i + 1,
            catchupDays = null,
        )
    }

    /** Resolves a `bbc:` URL to a playable HLS URL, trying each service and platform. */
    fun resolve(pseudoUrl: String): Resolved {
        val services = pseudoUrl.removePrefix(SCHEME).split(',').filter { it.isNotBlank() }
        var lastReason = "No BBC services listed"
        for (service in services) {
            for (platform in PLATFORMS) {
                val result = runCatching { select(service, platform) }
                    .getOrElse { Resolved.Failed(it.message ?: it.javaClass.simpleName) }
                when (result) {
                    is Resolved.Ok -> return result
                    is Resolved.Failed -> lastReason = result.reason
                }
            }
        }
        return Resolved.Failed(lastReason)
    }

    private fun select(service: String, platform: String): Resolved {
        val url = "https://open.live.bbc.co.uk/mediaselector/6/select/version/2.0/mediaset/$platform" +
            "/vpid/$service/format/json/atk/${atk(service)}/asn/1/"
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build())
            .execute().use { r -> r.body?.byteStream()?.let { json.decodeFromStream<JsonElement>(it) } }
            ?: return Resolved.Failed("Empty response")
        val obj = body as? JsonObject ?: return Resolved.Failed("Unexpected response")

        // Errors come back as {"result": "geolocation" | "selectionunavailable" | ...}
        obj.str("result")?.let { return Resolved.Failed(friendly(it)) }

        val media = (obj["media"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("kind") == "video" }
            .sortedByDescending { it.str("bitrate")?.toIntOrNull() ?: 0 }
        for (m in media) {
            val href = (m["connection"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.str("transferFormat") == "hls" && it.str("href")?.startsWith("https://") == true }
                .minByOrNull { it.str("priority")?.toIntOrNull() ?: Int.MAX_VALUE }
                ?.str("href")
            if (href != null) return Resolved.Ok(href, service)
        }
        return Resolved.Failed("No HLS stream offered for $service")
    }

    /** Today's and tomorrow's schedule for each channel, as programmes keyed by the channel's EPG id. */
    fun schedules(region: String, sourceId: Long): List<ProgrammeEntity> {
        val today = LocalDate.now(ZoneId.of("Europe/London"))
        return lineup(region).flatMap { ch ->
            listOf(today, today.plusDays(1)).flatMap { day ->
                runCatching { schedule(ch, day, sourceId) }
                    .onFailure { Log.w(TAG, "Schedule failed for ${ch.scheduleId} $day: ${it.message}") }
                    .getOrDefault(emptyList())
            }
        }
    }

    private fun schedule(ch: BbcChannel, day: LocalDate, sourceId: Long): List<ProgrammeEntity> {
        val url = "https://ibl.api.bbci.co.uk/ibl/v1/channels/${ch.scheduleId}/schedule/$day"
        val root = http.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build())
            .execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.byteStream()?.let { json.decodeFromStream<JsonElement>(it) }
            } ?: return emptyList()
        // Response shape isn't documented, so find broadcast-like objects wherever they are.
        return broadcasts(root).mapNotNull { b ->
            val start = b.str("scheduled_start")?.let(::parseTime) ?: return@mapNotNull null
            val end = b.str("scheduled_end")?.let(::parseTime) ?: return@mapNotNull null
            val episode = b["episode"] as? JsonObject
            val title = episode?.str("title") ?: b.str("title") ?: return@mapNotNull null
            val subtitle = episode?.str("subtitle")?.takeIf { it.isNotBlank() && it != title }
            val synopses = episode?.get("synopses") as? JsonObject
            ProgrammeEntity(
                channelKey = EPG_PREFIX + ch.key,
                start = start,
                stop = end,
                title = if (subtitle != null) "$title: $subtitle" else title,
                description = synopses?.str("medium") ?: synopses?.str("small"),
                sourceId = sourceId,
            )
        }
    }

    private fun broadcasts(el: JsonElement): List<JsonObject> = when (el) {
        is JsonObject -> if (el.containsKey("scheduled_start")) listOf(el)
            else el.values.flatMap { broadcasts(it) }
        is JsonArray -> el.flatMap { broadcasts(it) }
        else -> emptyList()
    }

    private fun parseTime(s: String): Long? =
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    private fun friendly(result: String) = when (result) {
        "geolocation" -> "BBC iPlayer is only available in the UK"
        "selectionunavailable" -> "Not currently broadcasting"
        else -> "BBC: $result"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    data class Region(val id: String, val label: String)

    companion object {
        private const val TAG = "BbcIplayer"
        const val SCHEME = "bbc:"
        const val GROUP = "BBC iPlayer"
        private const val EPG_PREFIX = "iplayer."
        private val PLATFORMS = listOf("pc", "iptv-all")
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

        // Media Selector request signature, as published in Streamlink's bbciplayer plugin.
        private const val ATK_KEY = "7dff7671d0c697fedb1d905d9a121719938b92bf"
        fun atk(service: String): String =
            MessageDigest.getInstance("SHA-1").digest((ATK_KEY + service).toByteArray())
                .joinToString("") { "%02x".format(it) }

        const val DEFAULT_REGION = "bbc_one_north_west"
        val REGIONS: Map<String, Region> = listOf(
            Region("bbc_one_london", "London"),
            Region("bbc_one_north_west", "North West"),
            Region("bbc_one_north_east", "North East & Cumbria"),
            Region("bbc_one_yorks", "Yorkshire"),
            Region("bbc_one_east_yorkshire", "East Yorkshire & Lincolnshire"),
            Region("bbc_one_east_midlands", "East Midlands"),
            Region("bbc_one_west_midlands", "West Midlands"),
            Region("bbc_one_east", "East"),
            Region("bbc_one_south", "South"),
            Region("bbc_one_south_east", "South East"),
            Region("bbc_one_south_west", "South West"),
            Region("bbc_one_west", "West"),
            Region("bbc_one_oxford", "Oxford"),
            Region("bbc_one_cambridge", "Cambridge"),
            Region("bbc_one_channel_islands", "Channel Islands"),
            Region("bbc_one_scotland_hd", "Scotland"),
            Region("bbc_one_wales_hd", "Wales"),
            Region("bbc_one_northern_ireland_hd", "Northern Ireland"),
        ).associateBy { it.id }

        fun isBbc(url: String) = url.startsWith(SCHEME)
    }
}
