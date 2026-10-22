package app.nullpointr.foxnav.server

import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    private val stylesDir = Files.createTempDirectory("foxnav-styles").also {
        it.resolve("night.json").writeText("""{"sources":{"m":{"url":"pmtiles://{{origin}}/tiles/fi.pmtiles"}}}""")
        it.resolve("day.json").writeText("{}")
        it.resolve("NotAStyle.txt").writeText("ignored")
    }

    private val config = ServerConfig(name = "Test server", stylesDir = stylesDir, regions = listOf("FI"))

    private fun serverTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { foxNavModule(config) }
        block()
    }

    @Test
    fun healthCheck() = serverTest {
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun discoveryDocumentListsServicesAndStyles() = serverTest {
        val response = client.get("/.well-known/foxnav")
        assertEquals(HttpStatusCode.OK, response.status)

        val document = DiscoveryDocument.parse(response.bodyAsText())
        assertEquals(DiscoveryDocument.CURRENT_VERSION, document.version)
        assertEquals("Test server", document.name)
        assertEquals(mapOf("day" to "/styles/day.json", "night" to "/styles/night.json"), document.styles)
        assertEquals("/valhalla", document.routing?.url)
        assertEquals("/photon", document.search?.url)
        assertEquals(listOf("FI"), document.regions)
        assertEquals(null, document.datapacks)
        assertEquals(null, document.routing?.tiles)
    }

    @Test
    fun routingTilesAreListedWhenConfigured() = testApplication {
        application { foxNavModule(config.copy(routingTilesUrl = "/routing-tiles")) }
        val document = DiscoveryDocument.parse(client.get("/.well-known/foxnav").bodyAsText())
        assertEquals("/routing-tiles", document.routing?.tiles)
    }

    @Test
    fun dataPacksAreListedWhenConfigured() = testApplication {
        application { foxNavModule(config.copy(datapacksUrl = "/datapacks")) }
        val document = DiscoveryDocument.parse(client.get("/.well-known/foxnav").bodyAsText())
        assertEquals("/datapacks", document.datapacks)
        assertTrue(Feature.DATA_PACKS in document.features)
    }

    @Test
    fun stylesAreRenderedWithTheOriginTheClientUsed() = serverTest {
        val response = client.get("/styles/night.json") { header(HttpHeaders.Host, "10.0.2.2:8080") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("pmtiles://http://10.0.2.2:8080/tiles/fi.pmtiles" in response.bodyAsText(), response.bodyAsText())
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun stylesRevalidateWithTheirETag() = serverTest {
        val first = client.get("/styles/night.json") { header(HttpHeaders.Host, "10.0.2.2:8080") }
        val etag = first.headers[HttpHeaders.ETag]!!
        val again = client.get("/styles/night.json") {
            header(HttpHeaders.Host, "10.0.2.2:8080")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, again.status)
        // Rendered for another origin, it's another style.
        val elsewhere = client.get("/styles/night.json") {
            header(HttpHeaders.Host, "localhost:8080")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.OK, elsewhere.status)
        assertTrue(elsewhere.headers[HttpHeaders.ETag] != etag)
    }

    @Test
    fun stylesBehindAProxyUseTheForwardedOrigin() = serverTest {
        val response = client.get("/styles/night.json") {
            header(HttpHeaders.XForwardedProto, "https")
            header(HttpHeaders.XForwardedHost, "maps.example.org")
        }
        assertTrue("pmtiles://https://maps.example.org/tiles/fi.pmtiles" in response.bodyAsText(), response.bodyAsText())
    }

    @Test
    fun unknownOrInvalidStylesAreNotFound() = serverTest {
        for (path in listOf("/styles/missing.json", "/styles/night", "/styles/NotAStyle.txt", "/styles/..%2Fnight.json")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }
    }

    @Test
    fun configIsReadFromTheEnvironment() {
        val parsed = ServerConfig.fromEnvironment(
            mapOf("FOXNAV_PORT" to "9000", "FOXNAV_NAME" to "Env", "FOXNAV_REGIONS" to "fi, se,", "FOXNAV_STYLES_DIR" to "/srv/styles"),
        )
        assertEquals(9000, parsed.port)
        assertEquals("Env", parsed.name)
        assertEquals(listOf("FI", "SE"), parsed.regions)
        assertEquals("/srv/styles", parsed.stylesDir.toString())
    }

    @Test
    fun settingsLeftEmptyAreUnset() {
        // compose.yaml passes FOXNAV_PRIVACY_URL through as "" when infra/.env doesn't set it.
        val parsed = ServerConfig.fromEnvironment(mapOf("FOXNAV_PRIVACY_URL" to "", "FOXNAV_REPORTS_FILE" to " "))
        assertNull(parsed.privacyUrl)
        assertNull(parsed.reportsFile)
        assertEquals("https://example.org/privacy", ServerConfig.fromEnvironment(mapOf("FOXNAV_PRIVACY_URL" to "https://example.org/privacy")).privacyUrl)
    }
}
