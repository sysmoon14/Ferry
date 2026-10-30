package dev.sysmoon.ferry.server

import android.util.Log
import dev.sysmoon.ferry.AppGraph
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

                // ---- Channels
                get("/groups") { call.respond(g.db.channels().groups()) }
                post("/groups/hidden") {
                    val req = call.receive<GroupPatch>()
                    g.db.channels().setGroupHidden(req.group, req.hidden)
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
}
