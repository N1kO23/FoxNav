package app.nullpointr.foxnav.server

import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRoutesTest {
    @Test
    fun theConvoyPageIsServedWithAStrictPolicy() = testApplication {
        application { foxNavModule(ServerConfig()) }
        val page = client.get(INVITE_PATH)
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
        assertTrue("web/js/join.js" in page.bodyAsText())
        val policy = page.headers["Content-Security-Policy"].orEmpty()
        assertTrue("script-src 'self'" in policy && "default-src 'none'" in policy, policy)
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
    }

    @Test
    fun scriptsAndModulesAreJavaScript() = testApplication {
        application { foxNavModule(ServerConfig()) }
        for (path in listOf("/web/js/join.js", "/web/js/convoy.js", "/web/vendor/maplibre-gl.mjs", "/web/vendor/maplibre-gl-worker.mjs", "/web/vendor/pmtiles.js")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals(ContentType.Text.JavaScript, response.contentType()?.withoutParameters(), path)
        }
        assertEquals(HttpStatusCode.OK, client.get("/web/vendor/maplibre-gl.css").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/web/test/vectors.test.js").status)
    }

    @Test
    fun discoveryPointsInvitesAtThePageAndAllowsOtherOrigins() = testApplication {
        application { foxNavModule(ServerConfig()) }
        val response = client.get(DiscoveryDocument.PATH)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(INVITE_PATH, DiscoveryDocument.parse(response.bodyAsText()).invites)
    }
}
