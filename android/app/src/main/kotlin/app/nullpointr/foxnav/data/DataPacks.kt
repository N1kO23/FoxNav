package app.nullpointr.foxnav.data

import android.content.Context
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.shared.safety.CameraPack
import app.nullpointr.foxnav.shared.speed.SpeedLimitPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The server's data packs (protocol/SPEC.md §3) for all its regions: speed cameras and speed
 * limits. Each is kept on the phone and revalidated with its ETag, so they work offline and cost
 * next to nothing to refresh.
 */
class DataPacks(context: Context, private val client: OkHttpClient) {
    private val directory = File(context.filesDir, "datapacks")

    private val _attributions = MutableStateFlow<Set<String>>(emptySet())

    /** Credits the loaded packs ask for, to show with the app's data sources. */
    val attributions: StateFlow<Set<String>> = _attributions.asStateFlow()

    suspend fun cameras(document: DiscoveryDocument): List<Camera> =
        load(document, "cameras.json") { CameraPack.parse(it).let { pack -> pack.cameras to pack.attribution } }.flatten()

    suspend fun speedLimits(document: DiscoveryDocument): List<SpeedLimitPack> =
        load(document, "speedlimits.json") { SpeedLimitPack.parse(it).let { pack -> pack to pack.attribution } }

    private suspend fun <T> load(document: DiscoveryDocument, name: String, parse: (String) -> Pair<T, String?>): List<T> = withContext(Dispatchers.IO) {
        val base = document.datapacks?.takeIf { Feature.DATA_PACKS in document.features } ?: return@withContext emptyList()
        document.regions.mapNotNull { region ->
            val parsed = fetch(base.trimEnd('/') + "/$region/$name")?.let { runCatching { parse(it) }.getOrNull() } ?: return@mapNotNull null
            parsed.second?.let { credit -> _attributions.update { it + credit } }
            parsed.first
        }
    }

    /** The pack's text: fresh, revalidated, or the copy we have when offline. Null if there's none. */
    private fun fetch(url: String): String? {
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val cached = File(directory, "$name.json")
        val etag = File(directory, "$name.etag")
        val request = Request.Builder().url(url).apply {
            if (cached.exists() && etag.exists()) header("If-None-Match", etag.readText())
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> cached.readText()
                    response.isSuccessful -> response.body.string().also { body ->
                        cached.writeText(body)
                        response.header("ETag")?.let(etag::writeText) ?: etag.delete()
                    }
                    // The server has no such pack (any more).
                    response.code == 404 -> {
                        cached.delete()
                        etag.delete()
                        null
                    }
                    else -> cached.takeIf { it.exists() }?.readText()
                }
            }
        }.getOrElse { cached.takeIf { it.exists() }?.readText() }
    }
}
