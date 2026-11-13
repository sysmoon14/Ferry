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

/** Groups, channels and favourites. */
internal fun Route.channelRoutes(api: ApiContext) {
    with(api) {
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
            g.db.channels().get(id) ?: throw NotFound("No such channel")
            val patch = call.receive<ChannelPatch>()
            patch.favourite?.let { g.db.channels().setFavourite(id, it) }
            patch.hidden?.let { g.db.channels().setHidden(id, it) }
            call.respond(OkDto())
        }
        get("/favourites") { call.respond(g.db.channels().favourites().map { it.toDto() }) }
        post("/favourites/order") {
            val ids = call.receive<OrderRequest>().ids
            g.db.withTransaction { ids.forEachIndexed { i, id -> g.db.channels().setSortOrder(id, i) } }
            call.respond(OkDto())
        }
    }
}
