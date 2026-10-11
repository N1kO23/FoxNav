package app.nullpointr.foxnav.shared.map

import app.nullpointr.foxnav.shared.FoxNavJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One credit a map style asks for, like "© OpenStreetMap", and where it links. */
data class MapCredit(val text: String, val url: String?)

/**
 * The credits in a MapLibre style: each source's `attribution`, which styles write as a little
 * HTML (`<a href="https://openstreetmap.org/copyright">© OpenStreetMap</a>`). Sources that
 * keep theirs in a separate TileJSON aren't followed.
 */
object MapCredits {
    /** What the FoxNav styles credit, and what any OpenStreetMap map must: shown until the style says otherwise. */
    val DEFAULT = listOf(MapCredit("© OpenStreetMap", "https://www.openstreetmap.org/copyright"))

    /** The credits in [styleJson], in order and without repeats; empty when it isn't a style or credits nothing. */
    fun parse(styleJson: String): List<MapCredit> {
        val sources = runCatching { FoxNavJson.parseToJsonElement(styleJson).jsonObject["sources"] as? JsonObject }.getOrNull() ?: return emptyList()
        return sources.values
            .mapNotNull { source -> ((source as? JsonObject)?.get("attribution") as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .flatMap(::credits)
            .distinctBy { it.text.lowercase() }
    }

    /** "Protomaps © OpenStreetMap": the credits as one short line. */
    fun line(credits: List<MapCredit>): String = credits.joinToString(" ") { it.text }

    private val LINK = Regex("""<a\s[^>]*href\s*=\s*["']([^"']*)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")

    /** A source's attribution HTML as credits: each link one, with the text between them (like "©") joined to the next. */
    private fun credits(html: String): List<MapCredit> {
        val credits = mutableListOf<MapCredit>()
        var position = 0
        var pending = ""
        for (match in LINK.findAll(html)) {
            pending += text(html.substring(position, match.range.first))
            val (url, label) = match.destructured
            credits += MapCredit(listOf(pending, text(label)).filter { it.isNotEmpty() }.joinToString(" "), url.takeIf { it.startsWith("https://") || it.startsWith("http://") })
            pending = ""
            position = match.range.last + 1
        }
        val rest = (pending + " " + text(html.substring(position))).trim()
        if (rest.isNotEmpty() && rest != "©") credits += MapCredit(rest, null)
        return credits.filter { it.text.isNotBlank() }
    }

    private fun text(html: String): String =
        html.replace(TAG, " ")
            .replace("&copy;", "©").replace("&amp;", "&").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
}
