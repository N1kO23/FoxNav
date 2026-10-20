package app.nullpointr.foxnav.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Serves MapLibre styles from templates. A template refers to this server as `{{origin}}`,
 * e.g. `"url": "pmtiles://{{origin}}/tiles/finland.pmtiles"`, and every request gets the
 * origin the client used. So one style works from `localhost`, from the Android emulator
 * (`10.0.2.2`), on the LAN, or behind a public domain with a TLS proxy.
 */
object StyleTemplates {
    private val validName = Regex("[a-z0-9][a-z0-9-]{0,63}")

    fun isValidName(name: String): Boolean = validName.matches(name)

    fun render(template: String, origin: String): String = template.replace("{{origin}}", origin)
}

fun Route.styleRoutes(config: ServerConfig) {
    get("/styles/{file}") {
        val file = call.parameters["file"].orEmpty()
        val name = file.removeSuffix(".json")
        val template = config.stylesDir
            ?.takeIf { file.endsWith(".json") && StyleTemplates.isValidName(name) }
            ?.resolve("$name.json")
            ?.takeIf { it.isRegularFile() }
            ?: return@get call.respond(HttpStatusCode.NotFound)

        val origin = call.request.origin
        val defaultPort = if (origin.scheme == "https") 443 else 80
        val port = if (origin.serverPort == defaultPort) "" else ":${origin.serverPort}"
        val body = StyleTemplates.render(template.readText(), "${origin.scheme}://${origin.serverHost}$port")
        // Map apps cache styles; revalidating keeps style updates from getting stuck behind old copies.
        // The ETag (of the style as rendered for this origin) lets them do that cheaply.
        val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).take(16).joinToString("") { "%02x".format(it) } + "\""
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        if (call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim() == etag } == true) {
            return@get call.respond(HttpStatusCode.NotModified)
        }
        call.respondText(body, ContentType.Application.Json)
    }
}
