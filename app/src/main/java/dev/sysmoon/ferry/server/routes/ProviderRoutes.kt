package dev.sysmoon.ferry.server.routes

import dev.sysmoon.ferry.data.ProviderList
import dev.sysmoon.ferry.player.PlayTarget
import dev.sysmoon.ferry.server.ApiContext
import dev.sysmoon.ferry.server.ErrorDto
import dev.sysmoon.ferry.server.OkDto
import dev.sysmoon.ferry.server.ProviderCategoryDto
import dev.sysmoon.ferry.server.ProviderPlayRequest
import dev.sysmoon.ferry.server.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The provider list: every source's categories and streams exactly as it sends them, duplicates
 * and hidden channels included, and playing one stream with no fallback.
 */
internal fun Route.providerRoutes(api: ApiContext) {
    with(api) {
        get("/provider/categories") {
            call.respond(g.repository.providerList.categories().map { ProviderCategoryDto(it.sourceId, it.source, it.name, it.count) })
        }
        // ?source=<id>&group=<name>; no group parameter = the streams without a category.
        get("/provider/streams") {
            val source = call.request.queryParameters["source"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("source is required"))
            val group = call.request.queryParameters["group"]?.takeIf { it.isNotBlank() }
            call.respond(g.repository.providerList.streams(source, group).map { it.toDto() })
        }
        get("/provider/search") {
            val q = call.request.queryParameters["q"].orEmpty().trim()
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
            if (q.length < 2) return@get call.respond(emptyList<dev.sysmoon.ferry.server.ProviderStreamDto>())
            val list = g.repository.providerList
            call.respond(g.db.channels().searchAll(q, limit).map { ProviderList.Stream(it, list.groupOf(it)).toDto() })
        }
        post("/provider/play") {
            val id = call.receive<ProviderPlayRequest>().id
            if (g.db.channels().get(id) == null) return@post call.respond(HttpStatusCode.NotFound, ErrorDto("No such stream"))
            g.commands.play(PlayTarget.Id(id, exact = true))
            call.respond(OkDto())
        }
    }
}
