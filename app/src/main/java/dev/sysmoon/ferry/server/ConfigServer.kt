package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.Logs
import dev.sysmoon.ferry.R
import dev.sysmoon.ferry.server.routes.channelRoutes
import dev.sysmoon.ferry.server.routes.debugRoutes
import dev.sysmoon.ferry.server.routes.deviceRoutes
import dev.sysmoon.ferry.server.routes.footballRoutes
import dev.sysmoon.ferry.server.routes.guideRoutes
import dev.sysmoon.ferry.server.routes.mqttRoutes
import dev.sysmoon.ferry.server.routes.playbackRoutes
import dev.sysmoon.ferry.server.routes.sourceRoutes
import dev.sysmoon.ferry.server.routes.syncRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * LAN config server. Pair once with the PIN shown on the TV; every other call needs
 * `Authorization: Bearer <token>` (or ?token= for the WebSocket, since browsers can't set headers).
 * The routes themselves live in `server/routes`, one file per area.
 */
class ConfigServer(private val g: AppGraph) {
    private var engine: EmbeddedServer<*, *>? = null
    private val api = ApiContext(g)

    // Brute-force guard for the 6-digit PIN.
    private val failedPins = AtomicInteger(0)
    private val lockedUntil = AtomicLong(0)

    fun start(port: Int) {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
        Logs.i(TAG, "Config server on :$port")
    }

    fun stop() {
        engine?.stop(500, 1_000)
        engine = null
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(api.json) }
        install(WebSockets)
        install(Authentication) {
            bearer(AUTH) {
                // Header for normal calls; ?token= for the WebSocket (browsers can't set headers there).
                authHeader { call -> call.bearerToken()?.let { HttpAuthHeader.Single("Bearer", it) } }
                authenticate { credential ->
                    if (g.settings.useToken(credential.token)) UserIdPrincipal("paired") else null
                }
            }
        }
        install(StatusPages) {
            // The caller's mistake: say what was wrong with the request.
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "Bad request")) }
            exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "Bad request")) }
            exception<ContentTransformationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "Bad request")) }
            exception<NotFound> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorDto(e.message ?: "Not found")) }
            // Ferry's mistake: log the detail, keep it out of the response.
            exception<Throwable> { call, e ->
                Logs.w(TAG, "API error on ${call.request.local.uri.substringBefore('?')}", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorDto("Ferry hit an error handling this (${e.javaClass.simpleName}); see /api/debug/log"))
            }
        }

        routing {
            get("/") { call.respondAsset("web/index.html", ContentType.Text.Html) }
            // The app icon and font, for the web page and as Home Assistant's picture when nothing is playing.
            get("/icon.png") { call.respondResource(R.drawable.ic_launcher, ContentType.Image.PNG) }
            get("/inter.ttf") { call.respondResource(R.font.inter, ContentType("font", "ttf")) }

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
                    g.settings.addToken(token, req.label ?: deviceLabel(call.request.headers["User-Agent"]))
                    call.respond(PairResponse(token))
                } else {
                    if (failedPins.incrementAndGet() >= 5) {
                        lockedUntil.set(now + 60_000)
                        failedPins.set(0)
                    }
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("Wrong PIN"))
                }
            }

            authenticate(AUTH) {
                route("/api") {
                    deviceRoutes(api)
                    sourceRoutes(api)
                    guideRoutes(api)
                    channelRoutes(api)
                    footballRoutes(api)
                    playbackRoutes(api)
                    syncRoutes(api)
                    mqttRoutes(api)
                    debugRoutes(api)
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondAsset(path: String, type: ContentType) {
        val bytes = g.context.assets.open(path).use { it.readBytes() }
        respondBytes(bytes, type)
    }

    private suspend fun ApplicationCall.respondResource(id: Int, type: ContentType) {
        val bytes = g.context.resources.openRawResource(id).use { it.readBytes() }
        response.headers.append("Cache-Control", "public, max-age=86400")
        respondBytes(bytes, type)
    }

    companion object {
        private const val TAG = "ConfigServer"
        private const val AUTH = "paired"

        /** A short name for a newly paired device, from its User-Agent ("iPhone · Safari"). */
        fun deviceLabel(userAgent: String?): String {
            val ua = userAgent.orEmpty()
            val device = when {
                "iPhone" in ua -> "iPhone"
                "iPad" in ua -> "iPad"
                "Android" in ua -> "Android"
                "Macintosh" in ua -> "Mac"
                "Windows" in ua -> "Windows PC"
                "Linux" in ua -> "Linux"
                else -> null
            }
            val browser = when {
                "HomeAssistant" in ua || "aiohttp" in ua -> "Home Assistant"
                "Edg/" in ua -> "Edge"
                "Firefox/" in ua || "FxiOS" in ua -> "Firefox"
                "Chrome/" in ua || "CriOS" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                "curl/" in ua -> "curl"
                else -> null
            }
            return listOfNotNull(device, browser).joinToString(" · ").ifBlank { ua.substringBefore(' ').take(40).ifBlank { "Unnamed device" } }
        }
    }
}
