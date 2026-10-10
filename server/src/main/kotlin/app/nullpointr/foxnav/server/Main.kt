package app.nullpointr.foxnav.server

import app.nullpointr.foxnav.server.convoy.ConvoyRelay
import app.nullpointr.foxnav.server.convoy.REALTIME_PATH
import app.nullpointr.foxnav.server.convoy.convoyRoutes
import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, host = config.host, port = config.port) { foxNavModule(config) }
        .start(wait = true)
}

fun Application.foxNavModule(config: ServerConfig, relay: ConvoyRelay = ConvoyRelay(ConvoyRelay.Limits(maxConvoySize = config.maxConvoySize))) {
    install(ContentNegotiation) { json(FoxNavJson) }
    // The server runs behind a reverse proxy (Caddy in infra/), which sets X-Forwarded-*.
    install(XForwardedHeaders)
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 45.seconds
        maxFrameSize = 64L * 1024
    }

    // Frees seats nobody resumed and convoys nobody came back to.
    launch {
        while (isActive) {
            delay(30.seconds)
            relay.sweep()
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        get(DiscoveryDocument.PATH) {
            call.respond(
                DiscoveryDocument(
                    version = DiscoveryDocument.CURRENT_VERSION,
                    name = config.name,
                    privacy = config.privacyUrl,
                    realtime = REALTIME_PATH,
                    datapacks = config.datapacksUrl,
                    styles = config.styleNames().associateWith { "/styles/$it.json" },
                    routing = ServiceEndpoint(type = "valhalla", url = config.routingUrl),
                    search = ServiceEndpoint(type = "photon", url = config.searchUrl),
                    features = setOfNotNull(Feature.CONVOY, Feature.DATA_PACKS.takeIf { config.datapacksUrl != null }),
                    regions = config.regions,
                ),
            )
        }

        styleRoutes(config)
        convoyRoutes(relay)
    }
}
