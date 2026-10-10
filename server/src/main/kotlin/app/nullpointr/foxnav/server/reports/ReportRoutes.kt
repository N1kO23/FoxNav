package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.reports.NewReport
import app.nullpointr.foxnav.shared.reports.ReportCreated
import app.nullpointr.foxnav.shared.reports.Vote
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/** Reports (protocol/SPEC.md §4): the feed, reporting, and "still there?" votes. */
fun Route.reportRoutes(store: ReportStore, limiter: RateLimiter = RateLimiter()) {
    // ETags differ between server starts, which restart the version count.
    val boot = UUID.randomUUID().toString().take(8)

    route(REPORTS_PATH) {
        get {
            val (version, feed) = store.feed()
            val etag = "\"$boot-$version\""
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            if (call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim().removePrefix("W/") == etag } == true) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                call.respond(feed)
            }
        }
        post {
            if (!limiter.allow("report:" + call.request.origin.remoteHost, REPORTS_PER_WINDOW, WINDOW_MS)) return@post call.respond(HttpStatusCode.TooManyRequests)
            val new = runCatching { call.receive<NewReport>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val id = store.add(new) ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.Created, ReportCreated(id))
        }
        post("{id}/votes") {
            if (!limiter.allow("vote:" + call.request.origin.remoteHost, VOTES_PER_WINDOW, WINDOW_MS)) return@post call.respond(HttpStatusCode.TooManyRequests)
            val vote = runCatching { call.receive<Vote>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val found = store.vote(call.parameters["id"].orEmpty(), vote.stillThere)
            call.respond(if (found) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
    }
}

const val REPORTS_PATH = "/v1/reports"
private const val REPORTS_PER_WINDOW = 5
private const val VOTES_PER_WINDOW = 30
private val WINDOW_MS = 10.minutes.inWholeMilliseconds
