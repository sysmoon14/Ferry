package dev.sysmoon.ferry.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Diagnostics for Xtream catch-up: panels disagree on the timeshift URL shape and on which time
 * zone the start time is written in. Tries the known variants from the TV itself and reports the
 * HTTP status of each, plus the panel's own archive listing for the stream. Credentials never
 * appear in the output.
 */
class CatchupProbe(private val http: OkHttpClient) {
    @Serializable data class Attempt(val label: String, val status: Int, val contentType: String?)
    @Serializable data class Report(
        val programme: String,
        val start: Long,
        val stop: Long,
        val serverTimezone: String?,
        val attempts: List<Attempt>,
        /** A few of the panel's own archive entries near the programme (raw, as the panel sends them). */
        val archive: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val liveUrl = Regex("""^(.*)/live/([^/]+)/([^/]+)/([^/.]+)\.\w+$""")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    fun probe(streamUrl: String, title: String, start: Long, stop: Long, serverTz: String?): Report {
        val (base, user, pass, id) = liveUrl.find(streamUrl)?.destructured
            ?: return Report(title, start, stop, serverTz, emptyList(), listOf("not an Xtream live URL"))
        val minutes = ((stop - start + 59_999) / 60_000).coerceAtLeast(1)
        val zones = buildList {
            serverTz?.let { runCatching { ZoneId.of(it) }.getOrNull() }?.let { add("server ${it.id}" to it) }
            add("UTC" to ZoneOffset.UTC)
            add("device ${ZoneId.systemDefault().id}" to ZoneId.systemDefault())
        }.distinctBy { it.second.normalized() }

        val attempts = mutableListOf<Attempt>()
        for ((zoneLabel, zone) in zones) {
            val s = Instant.ofEpochMilli(start).atZone(zone).format(stamp)
            listOf(
                "path .ts ($zoneLabel)" to "$base/timeshift/$user/$pass/$minutes/$s/$id.ts",
                "path .m3u8 ($zoneLabel)" to "$base/timeshift/$user/$pass/$minutes/$s/$id.m3u8",
                "timeshift.php ($zoneLabel)" to
                    "$base/streaming/timeshift.php?username=$user&password=$pass&stream=$id&start=$s&duration=$minutes",
            ).forEach { (label, url) -> attempts += attempt(label, url) }
        }
        return Report(title, start, stop, serverTz, attempts, archive(base, user, pass, id, start))
    }

    private fun attempt(label: String, url: String): Attempt = runCatching {
        http.newCall(
            Request.Builder().url(url).header("User-Agent", ChannelRepository.USER_AGENT)
                .header("Range", "bytes=0-1023").build()
        ).execute().use { Attempt(label, it.code, it.header("Content-Type")) }
    }.getOrElse { Attempt(label, -1, it.javaClass.simpleName + ": " + (it.message ?: "")) }

    /** get_simple_data_table lists the panel's archive with its own timestamps and has_archive flags. */
    private fun archive(base: String, user: String, pass: String, id: String, start: Long): List<String> = runCatching {
        val url = "$base/player_api.php?username=$user&password=$pass&action=get_simple_data_table&stream_id=$id"
        val root = http.newCall(Request.Builder().url(url).header("User-Agent", ChannelRepository.USER_AGENT).build())
            .execute().use { r -> json.parseToJsonElement(r.body?.string().orEmpty()) }
        val listings = ((root as? JsonObject)?.get("epg_listings") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val near = listings.sortedBy { kotlin.math.abs((it.str("start_timestamp")?.toLongOrNull() ?: 0) * 1000 - start) }
        near.take(4).map { e ->
            listOf("start", "end", "start_timestamp", "stop_timestamp", "has_archive", "now_playing")
                .joinToString(" ") { k -> "$k=${e.str(k)}" }
        }.ifEmpty { listOf("no epg_listings (${listings.size})") }
    }.getOrElse { listOf("archive lookup failed: ${it.javaClass.simpleName}") }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
