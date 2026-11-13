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

/** Status, device name, paired devices and the live status socket. */
internal fun Route.deviceRoutes(api: ApiContext) {
    with(api) {
        get("/status") { call.respond(g.player.state.value.toStatus(g.settings.deviceName(), g.player.stats())) }

        put("/device") {
            val req = call.receive<DeviceDto>()
            g.settings.setDeviceName(req.name)
            call.respond(OkDto())
        }

        // ---- Paired devices (phones, Home Assistant, other Ferry boxes that follow this one)
        get("/tokens") {
            val mine = call.bearerToken()?.let { dev.sysmoon.ferry.data.Settings.tokenId(it) }
            call.respond(g.settings.pairedDevices().map { PairedDeviceDto(it.id, it.label, it.pairedAt, it.lastUsedAt, it.id == mine) })
        }
        delete("/tokens/{id}") {
            g.settings.revokeToken(call.parameters["id"].orEmpty())
            call.respond(OkDto())
        }
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
}
