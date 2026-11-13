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

/** Sky layout, guide options, gap filling and guide search. */
internal fun Route.guideRoutes(api: ApiContext) {
    with(api) {
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
            o.idleStopHours?.let { g.settings.setIdleStopHours(it) }
            o.competitions?.let { names -> g.settings.setCompetitions(names.filter { n -> dev.sysmoon.ferry.data.Competition.entries.any { it.name == n } }.toSet()) }
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

        // Programmes whose title/description contains q, airing between from..to (epoch ms;
        // default: now..+12h), with every channel that carries that guide. Slow on a big guide, so
        // it runs in the background: 202 until it's ready, then the result (kept for two minutes).
        get("/guide/search") {
            val q = call.request.queryParameters["q"].orEmpty().trim().lowercase()
            val from = call.request.queryParameters["from"]?.toLongOrNull()
            val to = call.request.queryParameters["to"]?.toLongOrNull()
            // Keyed on what was asked for. It used to include the default times ("now"), which
            // differ on every request, so a poll never found its own result.
            val result = background.get("guide:$q:${from ?: "now"}:${to ?: "12h"}") { guideSearch(q, from, to) }
            if (result != null) call.respond(result)
            else call.respond(HttpStatusCode.Accepted, ErrorDto("working — ask again in a few seconds"))
        }
        get("/guide/search/now") {
            val q = call.request.queryParameters["q"].orEmpty().trim().lowercase()
            call.respond(guideSearch(q, call.request.queryParameters["from"]?.toLongOrNull(), call.request.queryParameters["to"]?.toLongOrNull()))
        }
    }
}
