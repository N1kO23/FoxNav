package app.nullpointr.foxnav.server

import app.nullpointr.foxnav.conformance.ConformanceSuite
import app.nullpointr.foxnav.conformance.Group
import app.nullpointr.foxnav.conformance.Outcome
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** The reference server passes the protocol conformance suite (map services aside: they run in Docker). */
class ConformanceTest {
    private val stylesDir = Files.createTempDirectory("foxnav-styles").also {
        it.resolve("night.json").writeText("""{"version":8,"sources":{},"layers":[]}""")
    }

    @Test
    fun theReferenceServerConforms() = testApplication {
        application { foxNavModule(ServerConfig(name = "Conformance", stylesDir = stylesDir, regions = listOf("FI"))) }
        val http = createClient { install(ClientWebSockets) }
        val results = ConformanceSuite(http, "http://localhost", groups = setOf(Group.DISCOVERY, Group.REALTIME)).run()

        val failures = results.filter { it.outcome == Outcome.FAIL }
        assertTrue(failures.isEmpty(), failures.joinToString("\n") { "${it.name}: ${it.detail}" })
        assertTrue(results.count { it.outcome == Outcome.PASS } >= 14, results.joinToString("\n"))
    }
}
