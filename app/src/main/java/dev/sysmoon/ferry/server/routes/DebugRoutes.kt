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

/** Diagnostics: catch-up probing, ESPN broadcast data, Ferry's log and last crash. */
internal fun Route.debugRoutes(api: ApiContext) {
    with(api) {
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
        // What ESPN knows about broadcasters, by region: ?event=<id>&league=eng.1&region=gb&lang=en
        // Returns every "broadcasts"/"geoBroadcasts" block found in the summary.
        get("/debug/espn") {
            val event = call.request.queryParameters["event"].orEmpty().filter { it.isDigit() }
            val league = call.request.queryParameters["league"]?.takeIf { it.matches(Regex("[a-z0-9.]+")) } ?: "eng.1"
            val region = call.request.queryParameters["region"]?.takeIf { it.matches(Regex("[a-z]{2}")) }
            val lang = call.request.queryParameters["lang"]?.takeIf { it.matches(Regex("[a-z]{2}")) }
            val url = buildString {
                append("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/summary?event=$event")
                region?.let { append("&region=$it") }
                lang?.let { append("&lang=$it") }
            }
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                g.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            }
            val found = mutableListOf<kotlinx.serialization.json.JsonElement>()
            fun walk(e: kotlinx.serialization.json.JsonElement, path: String) {
                when (e) {
                    is kotlinx.serialization.json.JsonObject -> e.forEach { (k, v) ->
                        if (k == "broadcasts" || k == "geoBroadcasts" || k == "broadcast") {
                            found += kotlinx.serialization.json.buildJsonObject { put("at", kotlinx.serialization.json.JsonPrimitive("$path.$k")); put("value", v) }
                        } else walk(v, "$path.$k")
                    }
                    is kotlinx.serialization.json.JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]") }
                    else -> Unit
                }
            }
            runCatching { walk(kotlinx.serialization.json.Json.parseToJsonElement(text), "") }
            call.respond(kotlinx.serialization.json.JsonArray(found))
        // Ferry's own log: the last few hundred lines, newest last (?n=200 for fewer).
        get("/debug/log") {
            val n = call.request.queryParameters["n"]?.toIntOrNull() ?: 600
            call.respondText(dev.sysmoon.ferry.Logs.dump(n), ContentType.Text.Plain)
        }
        get("/debug/crash") {
            val r = dev.sysmoon.ferry.CrashGuard.last.value
            call.respondText(
                if (r == null) "No crashes recorded." else buildString {
                    appendLine("${java.time.Instant.ofEpochMilli(r.at)} ${if (r.fatal) "crash" else "caught error"} in ${r.version} (thread ${r.thread}); ${r.count} so far; reopened: ${r.resumed}")
                    appendLine(r.stack)
                },
                ContentType.Text.Plain,
            )
        }
    }
}
