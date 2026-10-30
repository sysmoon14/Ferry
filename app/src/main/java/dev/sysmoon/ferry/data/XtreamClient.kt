package dev.sysmoon.ferry.data

import dev.sysmoon.ferry.parse.M3uEntry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.URLEncoder

/**
 * Xtream Codes JSON API (player_api.php). Fetches live channels only, unlike get.php,
 * whose M3U also carries every film and series episode (often 100k+ lines).
 *
 * Providers are inconsistent about types ("category_id": 12 vs "12", nulls, empty
 * strings), so everything is read leniently from JsonElements.
 */
@OptIn(ExperimentalSerializationApi::class)
class XtreamClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Account(val base: String, val username: String, val password: String) {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun api(action: String? = null) =
            "$base/player_api.php?username=${enc(username)}&password=${enc(password)}" +
                (action?.let { "&action=$it" } ?: "")
        fun liveUrl(streamId: String, ext: String = "ts") = "$base/live/$username/$password/$streamId.$ext"
        val epgUrl get() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"
    }

    companion object {
        private val LIVE_URL = Regex("""^(.*)/live/([^/]+)/([^/]+)/([^/.]+)\.\w+$""")
        private val TIMESHIFT_START = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

        /**
         * Xtream catch-up ("timeshift") URL for a live stream URL, or null if it isn't an Xtream
         * live URL. [start] is given in the server's time zone, [minutes] is the programme length.
         */
        fun timeshiftUrl(liveUrl: String, start: Long, stop: Long, timezone: String?): String? {
            val (base, user, pass, id) = LIVE_URL.find(liveUrl)?.destructured ?: return null
            val zone = runCatching { java.time.ZoneId.of(timezone ?: "") }.getOrNull() ?: java.time.ZoneId.systemDefault()
            val startText = java.time.Instant.ofEpochMilli(start).atZone(zone).format(TIMESHIFT_START)
            val minutes = ((stop - start + 59_999) / 60_000).coerceAtLeast(1)
            return "$base/timeshift/$user/$pass/$minutes/$startText/$id.ts"
        }
    }

    data class Live(val entries: List<M3uEntry>, val timezone: String?)

    /** Live channels as M3uEntry (shared import path with M3U), plus the server's time zone. */
    fun liveChannels(account: Account): Live {
        val timezone = checkAuth(account)
        val categoryList = getArray(account.api("get_live_categories")).mapNotNull { it.obj() }
        val categories = categoryList.associate { it.str("category_id").orEmpty() to it.str("category_name") }
        // The provider's category order is the group order users expect.
        val categoryRank = categoryList.withIndex().associate { (i, c) -> c.str("category_id").orEmpty() to i }

        val entries = getArray(account.api("get_live_streams")).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            o to (categoryRank[o.str("category_id")] ?: Int.MAX_VALUE)
        }.sortedBy { it.second } // stable: keeps the provider's order within each group
            .map { it.first }
            .mapNotNull { o ->
            val streamId = o.str("stream_id") ?: return@mapNotNull null
            if (o.str("stream_type")?.let { it != "live" && it != "created_live" } == true) return@mapNotNull null
            M3uEntry(
                name = o.str("name")?.trim().orEmpty().ifEmpty { "Channel $streamId" },
                url = account.liveUrl(streamId),
                tvgId = o.str("epg_channel_id")?.trim()?.takeIf { it.isNotEmpty() },
                tvgName = null,
                logo = o.str("stream_icon")?.takeIf { it.startsWith("http") },
                group = categories[o.str("category_id")],
                number = o.str("num")?.toIntOrNull(),
                catchupDays = o.str("tv_archive_duration")?.toIntOrNull()?.takeIf { o.str("tv_archive") == "1" },
            )
        }
        return Live(entries, timezone)
    }

    /**
     * player_api.php with no action returns user_info; auth failures come back as status/auth 0.
     * Returns server_info.timezone, which catch-up URLs are expressed in.
     */
    private fun checkAuth(account: Account): String? {
        val info = open(account.api()).use { json.decodeFromStream<JsonElement>(it) }.obj()
            ?: error("Xtream server didn't return account info — check the server URL")
        val user = info["user_info"]?.obj() ?: error("Xtream login failed — check username and password")
        if (user.str("auth") == "0") error("Xtream login failed — check username and password")
        user.str("status")?.let { status ->
            if (!status.equals("Active", ignoreCase = true)) error("Xtream account status: $status")
        }
        return (info["server_info"]?.obj())?.str("timezone")?.takeIf { it.isNotBlank() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun getArray(url: String): JsonArray =
        open(url).use { json.decodeFromStream<JsonElement>(it) }.let { el ->
            // Some panels return {} or {"user_info":...} instead of [] when empty or unauthorised.
            runCatching { el.jsonArray }.getOrElse { JsonArray(emptyList()) }
        }

    private fun open(url: String): InputStream {
        val response = http.newCall(
            Request.Builder().url(url).header("User-Agent", ChannelRepository.USER_AGENT).build()
        ).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} from ${ChannelRepository.redact(url)}")
        }
        return response.body!!.byteStream()
    }

    private fun JsonElement.obj(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
}
