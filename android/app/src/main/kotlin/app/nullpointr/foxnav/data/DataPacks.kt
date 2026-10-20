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
import java.io.File

/**
 * The server's data packs (protocol/SPEC.md §3) for all its regions: speed cameras and speed
 * limits. Each is kept on the phone and revalidated with its ETag, so they work offline and cost
 * next to nothing to refresh.
 */
class DataPacks(context: Context, client: OkHttpClient) {
    private val packs = CachedText(File(context.filesDir, "datapacks"), client)

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
            val parsed = packs.fetch(base.trimEnd('/') + "/$region/$name")?.let { runCatching { parse(it) }.getOrNull() } ?: return@mapNotNull null
            parsed.second?.let { credit -> _attributions.update { it + credit } }
            parsed.first
        }
    }
}
