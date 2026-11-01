package dev.sysmoon.ferry.server

import android.util.Log
import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.data.BbcIplayer
import dev.sysmoon.ferry.data.CatchupProbe
import dev.sysmoon.ferry.data.SkyLineup
import dev.sysmoon.ferry.data.epgKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.sysmoon.ferry.data.SourceEntity
import dev.sysmoon.ferry.mqtt.MqttBridge
import dev.sysmoon.ferry.player.PlayTarget
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import androidx.room.withTransaction
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * LAN config server. Pair once with the PIN shown on the TV; every other call needs
 * `Authorization: Bearer <token>` (or ?token= for the WebSocket, since browsers can't set headers).
 */
class ConfigServer(private val g: AppGraph) {
    private var engine: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Brute-force guard for the 6-digit PIN.
    private val failedPins = AtomicInteger(0)
    private val lockedUntil = AtomicLong(0)

    fun start(port: Int) {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
        Log.i(TAG, "Config server on :$port")
    }

    fun stop() {
        engine?.stop(500, 1_000)
        engine = null
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(Authentication) {
            bearer(AUTH) {
                // Header for normal calls; ?token= for the WebSocket (browsers can't set headers there).
                authHeader { call ->
                    call.request.queryParameters["token"]?.let { HttpAuthHeader.Single("Bearer", it) }
                        ?: call.request.parseAuthorizationHeader()
                }
                authenticate { credential ->
                    if (credential.token in g.settings.tokens()) UserIdPrincipal("paired") else null
                }
            }
        }
        install(StatusPages) {
            exception<Throwable> { call, e ->
                Log.w(TAG, "API error", e)
                call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: e.javaClass.simpleName))
            }
        }

        routing {
            get("/") { call.respondAsset("web/index.html", ContentType.Text.Html) }

            post("/api/pair") {
                val now = System.currentTimeMillis()
                if (now < lockedUntil.get()) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorDto("Too many attempts — wait a minute"))
                    return@post
                }
                val req = call.receive<PairRequest>()
                if (req.pin.filter(Char::isDigit) == g.settings.pin()) {
                    failedPins.set(0)
                    val token = g.settings.newToken()
                    g.settings.addToken(token)
                    call.respond(PairResponse(token))
                } else {
                    if (failedPins.incrementAndGet() >= 5) {
                        lockedUntil.set(now + 60_000)
                        failedPins.set(0)
                    }
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("Wrong PIN"))
                }
            }

            authenticate(AUTH) { route("/api") {
                get("/status") { call.respond(g.player.state.value.toStatus(g.settings.deviceName())) }

                put("/device") {
                    val req = call.receive<DeviceDto>()
                    g.settings.setDeviceName(req.name)
                    call.respond(OkDto())
                }

                // ---- Sources
                get("/sources") { call.respond(g.db.sources().all().map { it.toDto() }) }
                post("/sources") {
                    val input = call.receive<SourceInput>().validated()
                    val id = g.repository.addSource(
                        SourceEntity(
                            name = input.name, type = input.type, url = input.url, username = input.username,
                            password = input.password, epgUrl = input.epgUrl, refreshHours = input.refreshHours,
                        )
                    )
                    g.scope.launch { g.repository.refresh(id) }
                    call.respond(HttpStatusCode.Created, g.db.sources().get(id)!!.toDto())
                }
                put("/sources/{id}") {
                    val id = call.longParam("id")
                    val existing = g.db.sources().get(id) ?: return@put call.respond(HttpStatusCode.NotFound, ErrorDto("No such source"))
                    val input = call.receive<SourceInput>().validated()
                    g.db.sources().update(
                        existing.copy(
                            name = input.name, type = input.type, url = input.url, username = input.username,
                            password = input.password ?: existing.password, epgUrl = input.epgUrl,
                            refreshHours = input.refreshHours,
                        )
                    )
                    g.scope.launch { g.repository.refresh(id) }
                    call.respond(OkDto())
                }
                delete("/sources/{id}") {
                    g.repository.deleteSource(call.longParam("id"))
                    call.respond(OkDto())
                }
                post("/sources/{id}/refresh") {
                    val id = call.longParam("id")
                    g.scope.launch { g.repository.refresh(id) }
                    call.respond(HttpStatusCode.Accepted, OkDto())
                }

                // ---- BBC iPlayer
                get("/bbc/regions") {
                    call.respond(BbcIplayer.REGIONS.values.map { BbcRegionDto(it.id, it.label) })
                }
                // Resolves every BBC channel from this device (UK checks need the TV's own connection).
                get("/bbc/check") {
                    val region = call.request.queryParameters["region"] ?: BbcIplayer.DEFAULT_REGION
                    val results = withContext(Dispatchers.IO) {
                        g.bbc.lineup(region).map { ch ->
                            when (val r = g.bbc.resolve(BbcIplayer.SCHEME + ch.services.joinToString(","))) {
                                is BbcIplayer.Resolved.Ok -> BbcCheckDto(ch.name, true, r.service, r.url.substringBefore('?').take(120))
                                is BbcIplayer.Resolved.Failed -> BbcCheckDto(ch.name, false, null, r.reason)
                            }
                        }
                    }
                    call.respond(results)
                }

                // ---- Diagnostics: which catch-up URL shape this provider accepts.
                // ?channel=<id>[&ago=<minutes>] probes the latest programme that ended at least `ago` minutes ago.
                get("/debug/catchup") {
                    val id = call.request.queryParameters["channel"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("channel required"))
                    val ago = (call.request.queryParameters["ago"]?.toLongOrNull() ?: 0) * 60_000
                    val channel = g.db.channels().get(id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no such channel"))
                    val stream = g.player.streamsFor(channel).firstOrNull { it.catchupDays > 0 } ?: channel
                    val now = System.currentTimeMillis()
                    val programme = g.db.programmes().window(listOf(stream.epgKey), now - 6 * 3_600_000L, now)
                        .filter { it.stop <= now - ago }.maxByOrNull { it.start }
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no recent programme"))
                    val tz = g.db.sources().get(stream.sourceId)?.serverTimezone
                    val report = withContext(Dispatchers.IO) {
                        CatchupProbe(g.http).probe(stream.streamUrl, programme.title, programme.start, programme.stop, tz)
                    }
                    call.respond(report)
                }

                // ---- Sky layout
                get("/sky") {
                    if (g.settings.skyLayout() && g.repository.skyReport == null) g.repository.applySky()
                    call.respond(skyStatus())
                }
                put("/sky") {
                    val input = call.receive<SkyInput>()
                    val region = input.region.takeIf { it in SkyLineup.REGIONS } ?: SkyLineup.DEFAULT_REGION
                    g.settings.setSky(input.enabled, region, input.extraGroups.toSet())
                    g.repository.applySky()
                    g.scope.launch { g.repository.fillGuideGaps() }
                    call.respond(skyStatus())
                }
                // Hand-pick the playlist channel for a Sky number (channelId null clears the pick).
                put("/sky/override") {
                    val input = call.receive<SkyOverrideInput>()
                    val key = input.channelId?.let { id ->
                        val ch = g.db.channels().get(id) ?: return@put call.respond(HttpStatusCode.NotFound, ErrorDto("no such channel"))
                        val primary = ch.primaryId?.let { g.db.channels().get(it) } ?: ch
                        dev.sysmoon.ferry.data.streamKey(primary.sourceId, primary.streamUrl)
                    }
                    g.settings.setSkyOverride(input.number, key)
                    g.repository.applySky()
                    call.respond(skyStatus())
                }

                // ---- Options
                get("/options") { call.respond(options()) }
                put("/options") {
                    val o = call.receive<OptionsInput>()
                    o.resumeLastChannel?.let { g.settings.setResumeLastChannel(it) }
                    o.mergeDuplicates?.let { merge ->
                        val changed = merge != g.settings.mergeDuplicates()
                        g.settings.setMergeDuplicates(merge)
                        // Merging happens at import, so re-import to apply it.
                        if (changed) g.scope.launch { g.repository.refreshDue(force = true) }
                    }
                    o.sportsGuides?.let { on ->
                        g.settings.setSportsGuides(on)
                        g.scope.launch { g.repository.fillGuideGaps() }
                    }
                    o.backupEpgUrls?.let { urls ->
                        val clean = urls.map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
                        g.settings.setBackupEpgUrls(clean)
                        g.scope.launch { g.repository.fillGuideGaps() }
                    }
                    call.respond(options())
                }
                post("/guide/fill") {
                    g.repository.fillGuideGaps()
                    call.respond(options())
                }

                // ---- Channels
                get("/groups") { call.respond(g.db.channels().groups()) }
                post("/groups/hidden") {
                    val req = call.receive<GroupPatch>()
                    val hidden = g.settings.hiddenGroups()
                    g.settings.setHiddenGroups(if (req.hidden) hidden + req.group else hidden - req.group)
                    g.db.channels().setGroupHidden(req.group, req.hidden)
                    call.respond(OkDto())
                }
                get("/groups/detail") {
                    val hidden = g.settings.hiddenGroups()
                    call.respond(g.db.channels().groupSummary().map { GroupDto(it.name, it.count, it.name in hidden) })
                }
                // "Show only these groups": everything not listed is hidden, now and on future refreshes.
                put("/groups/visible") {
                    val visible = call.receive<VisibleGroupsRequest>().visible.toSet()
                    val all = g.db.channels().groups().toSet()
                    val hidden = all - visible
                    g.settings.setHiddenGroups(hidden)
                    g.db.withTransaction {
                        hidden.chunked(500).forEach { g.db.channels().setGroupsHidden(it, true) }
                        (all intersect visible).chunked(500).forEach { g.db.channels().setGroupsHidden(it, false) }
                    }
                    g.scope.launch { g.repository.applySky() } // visible channels changed
                    call.respond(OkDto())
                }
                get("/channels") {
                    val q = call.request.queryParameters["q"].orEmpty().trim()
                    val group = call.request.queryParameters["group"].orEmpty()
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                    val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    call.respond(
                        ChannelPage(
                            total = g.db.channels().count(q, group),
                            items = g.db.channels().search(q, group, limit, offset).map { it.toDto() },
                        )
                    )
                }
                patch("/channels/{id}") {
                    val id = call.parameters["id"]!!
                    val patch = call.receive<ChannelPatch>()
                    patch.favourite?.let { g.db.channels().setFavourite(id, it) }
                    patch.hidden?.let { g.db.channels().setHidden(id, it) }
                    call.respond(OkDto())
                }
                get("/favourites") { call.respond(g.db.channels().favourites().map { it.toDto() }) }

                // ---- Football finder diagnostics (also handy from the web page)
                get("/football/fixtures") {
                    val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 2).coerceIn(1, 7)
                    val all = dev.sysmoon.ferry.data.Competition.entries.flatMap {
                        runCatching { g.football.fixtures(it, dev.sysmoon.ferry.data.FootballData.today(), days) }.getOrDefault(emptyList())
                    }.sortedBy { it.kickoff }
                    call.respond(kotlinx.serialization.json.buildJsonArray {
                        all.forEach { f -> add(kotlinx.serialization.json.buildJsonObject {
                            put("id", kotlinx.serialization.json.JsonPrimitive(f.id))
                            put("title", kotlinx.serialization.json.JsonPrimitive(f.title))
                            put("competition", kotlinx.serialization.json.JsonPrimitive(f.competition.name))
                            put("kickoff", kotlinx.serialization.json.JsonPrimitive(java.time.Instant.ofEpochMilli(f.kickoff).toString()))
                            put("broadcasts", kotlinx.serialization.json.JsonArray(f.broadcasts.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        }) }
                    })
                }
                get("/football/candidates") {
                    val id = call.request.queryParameters["event"].orEmpty()
                    val all = dev.sysmoon.ferry.data.Competition.entries.flatMap {
                        runCatching { g.football.fixtures(it, dev.sysmoon.ferry.data.FootballData.today(), 3) }.getOrDefault(emptyList())
                    }
                    val f = all.firstOrNull { it.id == id }
                    if (f == null) { call.respond(HttpStatusCode.NotFound, ErrorDto("No such fixture")); return@get }
                    val sameSlot = all.count { it.competition == f.competition && kotlin.math.abs(it.kickoff - f.kickoff) <= 30 * 60_000L }
                    val found = g.matchFinder.find(f, sameSlot)
                    call.respond(kotlinx.serialization.json.buildJsonArray {
                        found.forEach { c -> add(kotlinx.serialization.json.buildJsonObject {
                            put("score", kotlinx.serialization.json.JsonPrimitive(c.score))
                            put("name", kotlinx.serialization.json.JsonPrimitive(c.channel.name))
                            put("group", kotlinx.serialization.json.JsonPrimitive(c.channel.groupTitle))
                            put("hidden", kotlinx.serialization.json.JsonPrimitive(c.channel.hidden))
                            put("reason", kotlinx.serialization.json.JsonPrimitive(c.reason))
                        }) }
                    })
                }
                // Programmes whose title/description contains q, airing between from..to (epoch ms;
                // default: now..+12h), with every channel that carries that guide.
                get("/guide/search") {
                    val q = call.request.queryParameters["q"].orEmpty().trim().lowercase()
                    val now = System.currentTimeMillis()
                    val from = call.request.queryParameters["from"]?.toLongOrNull() ?: now
                    val to = call.request.queryParameters["to"]?.toLongOrNull() ?: (now + 12 * 3_600_000L)
                    val rows = g.db.channels().allPrimaries().groupBy { it.epgKey }
                    val hits = g.db.programmes().spanning(to, from) // start <= to AND stop >= from
                        .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.description.orEmpty().lowercase().contains(q) }
                        .take(300)
                    call.respond(kotlinx.serialization.json.buildJsonArray {
                        hits.forEach { p -> add(kotlinx.serialization.json.buildJsonObject {
                            put("title", kotlinx.serialization.json.JsonPrimitive(p.title))
                            put("start", kotlinx.serialization.json.JsonPrimitive(java.time.Instant.ofEpochMilli(p.start).toString()))
                            put("guide", kotlinx.serialization.json.JsonPrimitive(p.channelKey))
                            put("channels", kotlinx.serialization.json.JsonArray(rows[p.channelKey].orEmpty().take(6).map {
                                kotlinx.serialization.json.JsonPrimitive((if (it.hidden) "[hidden] " else "") + it.name + " — " + it.groupTitle)
                            }))
                        }) }
                    })
                }
                post("/favourites/order") {
                    call.receive<OrderRequest>().ids.forEachIndexed { i, id -> g.db.channels().setSortOrder(id, i) }
                    call.respond(OkDto())
                }

                // ---- Playback (same paths HA can hit with rest_command)
                post("/play") {
                    val r = call.receive<PlayRequest>()
                    val target = r.channelId?.let { PlayTarget.Id(it) }
                        ?: r.tvgId?.let { PlayTarget.Tvg(it) }
                        ?: r.number?.let { PlayTarget.Number(it) }
                        ?: r.name?.let { PlayTarget.Name(it) }
                        ?: PlayTarget.Last
                    if (g.player.resolve(target) == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("No matching channel"))
                    } else {
                        g.commands.play(target)
                        call.respond(OkDto())
                    }
                }
                post("/key") {
                    val ok = g.commands.key(call.receive<KeyRequest>().key)
                    call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, OkDto(ok))
                }

                // ---- Backup & sync between devices
                get("/export") { call.respond(Backup.export(g)) }
                post("/import") {
                    val e = call.receive<FerryExport>()
                    call.respond(Backup.import(g, e))
                }

                // ---- Keep in sync with another Ferry
                get("/sync") { call.respond(g.sync.status()) }
                get("/sync/peers") { call.respond(g.sync.peers()) }
                put("/sync") {
                    val req = call.receive<FollowRequest>()
                    val result = runCatching { g.sync.follow(req.url, req.pin) }
                    result.onSuccess { call.respond(it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorDto(it.message ?: "Couldn't follow that Ferry")) }
                }
                post("/sync/now") { call.respond(g.sync.syncNow()) }
                delete("/sync") { call.respond(g.sync.stop()) }

                // ---- MQTT
                get("/mqtt") { call.respond(g.settings.mqtt().toDto()) }
                put("/mqtt") {
                    val input = call.receive<MqttInput>()
                    val current = g.settings.mqtt()
                    g.settings.setMqtt(input.toConfig(current.password))
                    call.respond(OkDto())
                }
                post("/mqtt/test") {
                    val input = call.receive<MqttInput>()
                    val result = MqttBridge.test(input.toConfig(g.settings.mqtt().password))
                    call.respond(TestResult(result.isSuccess, result.exceptionOrNull()?.message ?: "Connected"))
                }

                // ---- Pairing management
                post("/tokens/revoke") {
                    g.settings.revokeAllTokens()
                    call.respond(OkDto())
                }

                // ---- Live status push (web UI now; future HA integration)
                webSocket("/ws") {
                    val name = g.settings.deviceName()
                    g.player.state.collectLatest { state ->
                        send(Frame.Text(json.encodeToString(state.toStatus(name))))
                    }
                }
            } }
        }
    }

    private suspend fun ApplicationCall.respondAsset(path: String, type: ContentType) {
        val bytes = g.context.assets.open(path).use { it.readBytes() }
        respondBytes(bytes, type)
    }

    private fun ApplicationCall.longParam(name: String): Long =
        parameters[name]?.toLongOrNull() ?: throw IllegalArgumentException("Bad $name")

    private fun SourceInput.validated(): SourceInput {
        require(name.isNotBlank()) { "Name is required" }
        if (type == dev.sysmoon.ferry.data.SourceType.BBC) {
            // url carries the BBC One region; no credentials or EPG URL needed.
            val region = url.trim().takeIf { it in BbcIplayer.REGIONS } ?: BbcIplayer.DEFAULT_REGION
            return copy(name = name.trim(), url = region, username = null, password = null, epgUrl = null,
                refreshHours = refreshHours.coerceIn(1, 168))
        }
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL must start with http:// or https://" }
        require(refreshHours in 1..168) { "Refresh must be 1–168 hours" }
        return copy(name = name.trim(), url = url.trim(), epgUrl = epgUrl?.trim()?.ifBlank { null })
    }

    private fun MqttInput.toConfig(existingPassword: String) = dev.sysmoon.ferry.data.MqttConfig(
        enabled = enabled,
        host = host.trim(),
        port = port,
        username = username.trim(),
        password = password ?: existingPassword,
        discoveryPrefix = discoveryPrefix.trim(),
        baseTopic = baseTopic.trim().trim('/'),
    )

    companion object {
        private const val TAG = "ConfigServer"
        private const val AUTH = "paired"
    }

    private suspend fun options() = OptionsDto(
        mergeDuplicates = g.settings.mergeDuplicates(),
        resumeLastChannel = g.settings.resumeLastChannel(),
        backupEpgUrls = g.settings.backupEpgUrls(),
        sportsGuides = g.settings.sportsGuides(),
        guideGaps = g.repository.gapReport?.let { GuideGapsDto(it.missing, it.fromSky, it.fromBackup, it.errors, it.at, it.fromSportsGuides) },
    )

    private suspend fun skyStatus(): SkyStatusDto {
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
}
