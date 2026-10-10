package app.nullpointr.foxnav.data

import android.content.Context
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.shared.safety.CameraPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The server's speed camera packs (protocol/SPEC.md §3.1) for all its regions. Each is kept on the
 * phone and revalidated with its ETag, so warnings work offline and cost next to nothing to refresh.
 */
class CameraPacks(context: Context, private val client: OkHttpClient) {
    private val directory = File(context.filesDir, "datapacks")

    suspend fun load(document: DiscoveryDocument): List<Camera> = withContext(Dispatchers.IO) {
        val base = document.datapacks?.takeIf { Feature.DATA_PACKS in document.features } ?: return@withContext emptyList()
        document.regions.flatMap { region -> load(base.trimEnd('/') + "/$region/cameras.json") }
    }

    private fun load(url: String): List<Camera> {
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val cached = File(directory, "$name.json")
        val etag = File(directory, "$name.etag")
        val request = Request.Builder().url(url).apply {
            if (cached.exists() && etag.exists()) header("If-None-Match", etag.readText())
        }.build()
        val text = runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> cached.readText()
                    response.isSuccessful -> response.body.string().also { body ->
                        cached.writeText(body)
                        response.header("ETag")?.let(etag::writeText) ?: etag.delete()
                    }
                    else -> null
                }
            }
        }.getOrNull() ?: cached.takeIf { it.exists() }?.readText() // offline: the copy we have
        return text?.let { runCatching { CameraPack.parse(it).cameras }.getOrNull() }.orEmpty()
    }
}
