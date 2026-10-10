package app.nullpointr.foxnav.server

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.ServiceEndpoint
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, host = config.host, port = config.port) { foxNavModule(config) }
        .start(wait = true)
}

fun Application.foxNavModule(config: ServerConfig) {
    install(ContentNegotiation) { json(FoxNavJson) }
    // The server runs behind a reverse proxy (Caddy in infra/), which sets X-Forwarded-*.
    install(XForwardedHeaders)
    install(CallLogging)

    routing {
        get("/healthz") { call.respondText("ok") }

        get(DiscoveryDocument.PATH) {
            call.respond(
                DiscoveryDocument(
                    version = DiscoveryDocument.CURRENT_VERSION,
                    name = config.name,
                    privacy = config.privacyUrl,
                    styles = config.styleNames().associateWith { "/styles/$it.json" },
                    routing = ServiceEndpoint(type = "valhalla", url = config.routingUrl),
                    search = ServiceEndpoint(type = "photon", url = config.searchUrl),
                    regions = config.regions,
                ),
            )
        }

        styleRoutes(config)
    }
}
