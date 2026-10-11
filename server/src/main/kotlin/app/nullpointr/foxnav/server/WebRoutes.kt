package app.nullpointr.foxnav.server

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Where invite links point: the convoy page (protocol/SPEC.md §2.5). */
const val INVITE_PATH = "/join"

/**
 * The web convoy page and its files (web/ in the repository). The page holds the convoy's key, so
 * it gets a strict content security policy: scripts only from here, and other people's names and
 * messages only ever as text.
 */
fun Route.webRoutes() {
    val page = WebRoutes::class.java.classLoader.getResource("web/join.html")?.readBytes()
    get(INVITE_PATH) {
        if (page == null) return@get call.respond(HttpStatusCode.NotFound)
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(page, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    staticResources("/web", "web") {
        // Modules must come as JavaScript, or browsers refuse them.
        contentType { url -> if (url.path.endsWith(".mjs") || url.path.endsWith(".js")) ContentType.Text.JavaScript else null }
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 300)) }
        modify { _, call -> call.response.header("X-Content-Type-Options", "nosniff") }
    }
}

private object WebRoutes

// Map data (tiles, glyphs, sprites) and the convoy's server can be anywhere the invite and style say.
private const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: http: https:; " +
        "connect-src 'self' http: https: ws: wss:; worker-src 'self' blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
