package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.reports.NewReport
import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportCreated
import app.nullpointr.foxnav.shared.reports.ReportFeed
import app.nullpointr.foxnav.shared.reports.Vote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The server's reports (protocol/SPEC.md §4): the feed, revalidated with its ETag, reporting and votes. */
class ReportsClient(private val client: OkHttpClient, url: String) {
    private val url = url.trimEnd('/')
    private var etag: String? = null
    private var latest: List<Report> = emptyList()

    /** The current reports; throws when the server can't be reached. */
    suspend fun feed(): List<Report> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { etag?.let { header("If-None-Match", it) } }.build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> latest
                response.isSuccessful -> ReportFeed.parse(response.body.string()).reports.also {
                    latest = it
                    etag = response.header("ETag")
                }
                else -> error("HTTP ${response.code}")
            }
        }
    }

    /** Reports something where we are; returns its ID. */
    suspend fun report(new: NewReport): String = withContext(Dispatchers.IO) {
        val body = FoxNavJson.encodeToString(NewReport.serializer(), new).toRequestBody(JSON)
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            FoxNavJson.decodeFromString(ReportCreated.serializer(), response.body.string()).id
        }
    }

    /** Says whether report [id] is still there. A report that's gone already counts as done. */
    suspend fun vote(id: String, stillThere: Boolean) = withContext(Dispatchers.IO) {
        val body = FoxNavJson.encodeToString(Vote.serializer(), Vote(stillThere)).toRequestBody(JSON)
        val voteUrl = url.toHttpUrl().newBuilder().addPathSegment(id).addPathSegment("votes").build()
        client.newCall(Request.Builder().url(voteUrl).post(body).build()).execute().use { response ->
            check(response.isSuccessful || response.code == 404) { "HTTP ${response.code}" }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
