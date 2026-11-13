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

/** Playlists (M3U, Xtream, BBC iPlayer). */
internal fun Route.sourceRoutes(api: ApiContext) {
    with(api) {
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
    }
}
