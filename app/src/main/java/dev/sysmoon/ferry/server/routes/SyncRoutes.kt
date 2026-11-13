package dev.sysmoon.ferry.server.routes

import androidx.room.withTransaction
import dev.sysmoon.ferry.Logs
import dev.sysmoon.ferry.data.BbcIplayer
import dev.sysmoon.ferry.data.CatchupProbe
import dev.sysmoon.ferry.data.SkyLineup
import dev.sysmoon.ferry.data.SourceEntity
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.mqtt.MqttBridge
import dev.sysmoon.ferry.player.PlayTarget
import dev.sysmoon.ferry.server.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Backup, restore and keeping one Ferry in sync with another. */
internal fun Route.syncRoutes(api: ApiContext) {
    with(api) {
        // Passwords (playlist logins, the MQTT password) are left out unless ?secrets=1 is given.
        // A Ferry that follows this one asks for them; an older follower that doesn't know to ask
        // (it identifies as plain okhttp) still gets them, so it keeps working until it updates.
        get("/export") {
            val asked = call.request.queryParameters["secrets"] == "1"
            val legacyFollower = call.request.headers["User-Agent"].orEmpty().startsWith("okhttp/")
            call.respond(Backup.export(g, secrets = asked || legacyFollower))
        }
        post("/import") {
            val e = call.receive<FerryExport>()
            call.respond(Backup.import(g, e))
        }

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
    }
}
