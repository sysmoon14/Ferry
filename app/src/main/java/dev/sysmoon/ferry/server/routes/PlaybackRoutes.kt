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

/** Playback control (the same paths Home Assistant can call with rest_command). */
internal fun Route.playbackRoutes(api: ApiContext) {
    with(api) {
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
    }
}
