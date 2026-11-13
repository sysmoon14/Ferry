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

/** Football finder: fixtures and the streams that might show a match. */
internal fun Route.footballRoutes(api: ApiContext) {
    with(api) {
        get("/football/fixtures") {
            val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 2).coerceIn(1, 7)
            val all = dev.sysmoon.ferry.data.Competition.enabled(g.settings.competitions()).flatMap {
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
        // Slow on big playlists: computed in the background; poll until it's ready.
        get("/football/candidates") {
            val id = call.request.queryParameters["event"].orEmpty()
            val result = background.get("football:$id") { candidates(id) ?: JsonPrimitive("no such fixture") }
            if (result != null) call.respond(result)
            else call.respond(HttpStatusCode.Accepted, ErrorDto("working — ask again in a few seconds"))
        }
        get("/football/candidates/now") {
            val id = call.request.queryParameters["event"].orEmpty()
            call.respond(candidates(id) ?: throw NotFound("No such fixture"))
        }
    }
}
