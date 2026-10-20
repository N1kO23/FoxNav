package app.nullpointr.foxnav.data

import android.content.Context
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.theme.PaletteColors
import app.nullpointr.foxnav.shared.theme.StyleRecolour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * The server's map styles (protocol/SPEC.md §1), kept on the phone: the map takes them from here,
 * recoloured in the driver's palette, and switches themes without a connection.
 */
class MapStyles(context: Context, client: OkHttpClient, private val online: StateFlow<Boolean>, private val scope: CoroutineScope) {
    private val cache = CachedText(File(context.filesDir, "styles"), client)
    private val _styles = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Each style we have, by URL. */
    val styles: StateFlow<Map<String, String>> = _styles.asStateFlow()

    private var refreshing: Job? = null
    private val styled = object : LinkedHashMap<Styled, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Styled, String>) = size > 4
    }

    /** Keeps [document]'s styles: fresh ones whenever there's a connection. */
    fun use(document: DiscoveryDocument) {
        val urls = document.styles.values.distinct()
        refreshing?.cancel()
        refreshing = scope.launch(Dispatchers.IO) {
            online.filter { it }.collect {
                for (url in urls) cache.fetch(url)?.let { json -> _styles.update { it + (url to json) } }
            }
        }
    }

    /** The style at [url] as we have it, from memory or the phone; null before it was ever fetched. */
    fun style(url: String): String? = _styles.value[url] ?: cache.stored(url)?.also { json -> _styles.update { it + (url to json) } }

    /**
     * [style] in [colors] (null keeps the server's) with labels [labelScale] times as big. The
     * last few are kept, so switching between day and night costs nothing.
     */
    @Synchronized
    fun styled(style: String, colors: PaletteColors?, labelScale: Double): String {
        val recolour = colors?.takeIf { StyleRecolour.hasTemplates(style) }
        if (recolour == null && labelScale == 1.0) return style
        val key = Styled(style.hashCode(), style.length, recolour, labelScale)
        return styled.getOrPut(key) { StyleRecolour.apply(style, recolour, labelScale) }
    }

    private data class Styled(val hash: Int, val length: Int, val colors: PaletteColors?, val labelScale: Double)
}
