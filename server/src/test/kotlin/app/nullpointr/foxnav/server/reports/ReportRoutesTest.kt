package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.server.ServerConfig
import app.nullpointr.foxnav.server.foxNavModule
import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.reports.ReportCreated
import app.nullpointr.foxnav.shared.reports.ReportFeed
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReportRoutesTest {
    private fun reportsTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { foxNavModule(ServerConfig()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, json: String) = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    @Test
    fun reportsAreListedInDiscovery() = reportsTest {
        val document = DiscoveryDocument.parse(client.get(DiscoveryDocument.PATH).bodyAsText())
        assertEquals(REPORTS_PATH, document.reports)
        assertTrue(Feature.REPORTS in document.features)
    }

    @Test
    fun aReportShowsUpInTheFeed() = reportsTest {
        val created = postJson(REPORTS_PATH, """{"type":"police","lat":60.17,"lon":24.94,"bearing":180}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val id = FoxNavJson.decodeFromString(ReportCreated.serializer(), created.bodyAsText()).id

        val feed = ReportFeed.parse(client.get(REPORTS_PATH).bodyAsText())
        val report = feed.reports.single()
        assertEquals(id, report.id)
        assertEquals("police", report.type)
        assertEquals(180.0, report.bearing)
        assertEquals(null, report.source)
    }

    @Test
    fun badReportsAreRefused() = reportsTest {
        assertEquals(HttpStatusCode.BadRequest, postJson(REPORTS_PATH, """{"type":"ufo_landing","lat":60.17,"lon":24.94}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson(REPORTS_PATH, """{"lat":60.17}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson(REPORTS_PATH, "not json").status)
    }

    @Test
    fun theFeedAnswersNotModifiedUntilItChanges() = reportsTest {
        val first = client.get(REPORTS_PATH)
        val etag = first.headers[HttpHeaders.ETag]!!
        assertEquals("no-cache", first.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.NotModified, client.get(REPORTS_PATH) { header(HttpHeaders.IfNoneMatch, etag) }.status)

        postJson(REPORTS_PATH, """{"type":"hazard","lat":60.17,"lon":24.94}""")
        val changed = client.get(REPORTS_PATH) { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertNotEquals(etag, changed.headers[HttpHeaders.ETag])
    }

    @Test
    fun votesConfirmOrRemoveReports() = reportsTest {
        val id = FoxNavJson.decodeFromString(ReportCreated.serializer(), postJson(REPORTS_PATH, """{"type":"hazard","lat":60.17,"lon":24.94}""").bodyAsText()).id
        assertEquals(HttpStatusCode.NoContent, postJson("$REPORTS_PATH/$id/votes", """{"still_there":true}""").status)
        assertEquals(1, ReportFeed.parse(client.get(REPORTS_PATH).bodyAsText()).reports.single().confirmations)
        repeat(3) { postJson("$REPORTS_PATH/$id/votes", """{"still_there":false}""") }
        assertTrue(ReportFeed.parse(client.get(REPORTS_PATH).bodyAsText()).reports.isEmpty())
        assertEquals(HttpStatusCode.NotFound, postJson("$REPORTS_PATH/$id/votes", """{"still_there":true}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("$REPORTS_PATH/x/votes", """{"maybe":1}""").status)
    }

    @Test
    fun reportingIsRateLimited() = reportsTest {
        val statuses = (1..6).map { postJson(REPORTS_PATH, """{"type":"hazard","lat":60.17,"lon":24.94}""").status }
        assertEquals(List(5) { HttpStatusCode.Created } + HttpStatusCode.TooManyRequests, statuses)
    }
}
