package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.data.BbcIplayer
import dev.sysmoon.ferry.data.Competition
import dev.sysmoon.ferry.data.FootballData
import dev.sysmoon.ferry.data.MqttConfig
import dev.sysmoon.ferry.data.SkyLineup
import dev.sysmoon.ferry.data.SourceType
import dev.sysmoon.ferry.data.epgKey
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.parseAuthorizationHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** A request for something that isn't there: answered with 404. */
class NotFound(message: String) : RuntimeException(message)

/** What every group of routes needs: the app graph and the helpers several of them share. */
internal class ApiContext(val g: AppGraph) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val background = BackgroundResults(g.scope)

    suspend fun options() = OptionsDto(
        mergeDuplicates = g.settings.mergeDuplicates(),
        resumeLastChannel = g.settings.resumeLastChannel(),
        backupEpgUrls = g.settings.backupEpgUrls(),
        sportsGuides = g.settings.sportsGuides(),
        idleStopHours = g.settings.idleStopHours(),
        competitions = g.settings.competitions().toList(),
        allCompetitions = Competition.entries.map { CompetitionDto(it.name, it.label) },
        guideGaps = g.repository.gapReport?.let { GuideGapsDto(it.missing, it.fromSky, it.fromBackup, it.errors, it.at, it.fromSportsGuides) },
    )

    suspend fun skyStatus(): SkyStatusDto {
        val report = g.repository.skyReport
        return SkyStatusDto(
            enabled = g.settings.skyLayout(),
            region = g.settings.skyRegion(),
            regions = SkyLineup.REGIONS.map { (id, name) -> SkyRegionDto(id, name) },
            extraGroups = g.settings.skyExtraGroups().toList(),
            matched = report?.matched ?: 0,
            total = report?.total ?: 0,
            unmatched = report?.unmatched.orEmpty().map { SkyChannelDto(it.number, it.title, it.section) },
            error = report?.error,
            notes = report?.notes.orEmpty().map { SkyNoteDto(it.number, it.title, it.channel, it.how) },
        )
    }

    /**
     * Programmes whose title or description contains [q], airing between [from] and [to] (epoch ms;
     * default now to +12h), with the channels that carry that guide.
     */
    suspend fun guideSearch(q: String, from: Long?, to: Long?): JsonElement {
        val now = System.currentTimeMillis()
        val start = from ?: now
        val end = to ?: (now + 12 * 3_600_000L)
        val rows = g.db.channels().allPrimaries().groupBy { it.epgKey }
        val hits = g.db.programmes().spanning(end, start, end + 12 * 3_600_000L) // start <= end AND stop >= start
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.description.orEmpty().lowercase().contains(q) }
            .take(300)
        return buildJsonArray {
            hits.forEach { p ->
                add(buildJsonObject {
                    put("title", JsonPrimitive(p.title))
                    put("start", JsonPrimitive(Instant.ofEpochMilli(p.start).toString()))
                    put("guide", JsonPrimitive(p.channelKey))
                    put("channels", JsonArray(rows[p.channelKey].orEmpty().take(6).map {
                        JsonPrimitive((if (it.hidden) "[hidden] " else "") + it.name + " — " + it.groupTitle)
                    }))
                })
            }
        }
    }

    /** The streams that might show fixture [id], best first; null when there's no such fixture. */
    suspend fun candidates(id: String): JsonElement? {
        val all = Competition.enabled(g.settings.competitions()).flatMap {
            runCatching { g.football.fixtures(it, FootballData.today(), 7) }.getOrDefault(emptyList())
        }
        val f = all.firstOrNull { it.id == id } ?: return null
        val sameSlot = all.count { it.competition == f.competition && kotlin.math.abs(it.kickoff - f.kickoff) <= 30 * 60_000L }
        val found = g.matchFinder.find(f, sameSlot).last()
        return buildJsonArray {
            found.forEach { c ->
                add(buildJsonObject {
                    put("score", JsonPrimitive(c.score))
                    put("name", JsonPrimitive(c.channel.name))
                    put("group", JsonPrimitive(c.channel.groupTitle))
                    put("hidden", JsonPrimitive(c.channel.hidden))
                    put("reason", JsonPrimitive(c.reason))
                })
            }
        }
    }
}

/**
 * Slow answers computed in the background: the first request starts the work and gets nothing;
 * later requests with the same key get the result once it's ready. Results are kept for
 * [ttlMs], then dropped, so the map can't grow without limit.
 */
internal class BackgroundResults(private val scope: CoroutineScope, private val ttlMs: Long = 120_000L) {
    private class Entry(val at: Long, val value: JsonElement)
    private val done = ConcurrentHashMap<String, Entry>()
    private val running: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun get(key: String, work: suspend () -> JsonElement): JsonElement? {
        val now = System.currentTimeMillis()
        done.entries.removeIf { now - it.value.at > ttlMs }
        done[key]?.let { return it.value }
        if (running.add(key)) scope.launch {
            try {
                val value = runCatching { work() }
                    .getOrElse { e -> buildJsonObject { put("error", JsonPrimitive(e.message ?: e.javaClass.simpleName)) } }
                done[key] = Entry(System.currentTimeMillis(), value)
            } finally {
                running.remove(key)
            }
        }
        return null
    }
}

/** The bearer token of this call: the Authorization header, or ?token= (the WebSocket can't set headers). */
internal fun ApplicationCall.bearerToken(): String? =
    request.queryParameters["token"]
        ?: (request.parseAuthorizationHeader() as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob

internal fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw IllegalArgumentException("Bad $name")

internal fun SourceInput.validated(): SourceInput {
    require(name.isNotBlank()) { "Name is required" }
    if (type == SourceType.BBC) {
        // url carries the BBC One region; no credentials or EPG URL needed.
        val region = url.trim().takeIf { it in BbcIplayer.REGIONS } ?: BbcIplayer.DEFAULT_REGION
        return copy(name = name.trim(), url = region, username = null, password = null, epgUrl = null,
            refreshHours = refreshHours.coerceIn(1, 168))
    }
    // Trimmed first: a URL pasted with a space in front of it used to be turned away.
    val address = url.trim()
    require(address.startsWith("http://") || address.startsWith("https://")) { "URL must start with http:// or https://" }
    require(refreshHours in 1..168) { "Refresh must be 1–168 hours" }
    return copy(name = name.trim(), url = address, epgUrl = epgUrl?.trim()?.ifBlank { null })
}

internal fun MqttInput.toConfig(existingPassword: String) = MqttConfig(
    enabled = enabled,
    host = host.trim(),
    port = port,
    username = username.trim(),
    password = password ?: existingPassword,
    discoveryPrefix = discoveryPrefix.trim(),
    baseTopic = baseTopic.trim().trim('/'),
    tls = tls,
)
