package app.nullpointr.foxnav.shared.discovery

private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * Resolves [reference] against [base] like a browser resolves a link (RFC 3986 section 5.2).
 * [base] must be an absolute hierarchical URL such as `https://maps.example.org/.well-known/foxnav`.
 */
fun resolveUrl(base: String, reference: String): String {
    if (schemePrefix.containsMatchIn(reference)) return reference

    val schemeEnd = base.indexOf("://")
    require(schemeEnd > 0) { "Base URL must be absolute: $base" }
    val scheme = base.substring(0, schemeEnd)
    val afterScheme = base.substring(schemeEnd + 3)
    val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    val rest = afterScheme.substring(authorityEnd)
    val basePath = rest.substringBefore('#').substringBefore('?')
    val baseQuery = rest.substringBefore('#').substringAfter('?', missingDelimiterValue = "")

    return when {
        reference.startsWith("//") -> "$scheme:$reference"
        reference.startsWith("/") -> "$scheme://$authority${removeDotSegments(reference)}"
        reference.isEmpty() -> "$scheme://$authority$basePath" + if (baseQuery.isEmpty()) "" else "?$baseQuery"
        reference.startsWith("?") -> "$scheme://$authority$basePath$reference"
        reference.startsWith("#") -> "$scheme://$authority$basePath" + (if (baseQuery.isEmpty()) "" else "?$baseQuery") + reference
        else -> {
            val directory = if (basePath.isEmpty()) "/" else basePath.substringBeforeLast('/') + "/"
            "$scheme://$authority${removeDotSegments(directory + reference)}"
        }
    }
}

/** Switches an `http`/`https` URL to `ws`/`wss`; other URLs are returned unchanged. */
fun toWebSocketUrl(url: String): String = when {
    url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
    url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
    else -> url
}

/** RFC 3986 section 5.2.4, applied to the path part only (query and fragment are kept as-is). */
private fun removeDotSegments(pathAndMore: String): String {
    val suffixStart = pathAndMore.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) pathAndMore.length else it }
    val path = pathAndMore.substring(0, suffixStart)
    val suffix = pathAndMore.substring(suffixStart)

    val output = ArrayList<String>()
    val segments = path.split('/')
    for ((index, segment) in segments.withIndex()) {
        val isLast = index == segments.lastIndex
        when (segment) {
            "." -> if (isLast) output.add("")
            ".." -> {
                if (output.size > 1) output.removeAt(output.lastIndex)
                if (isLast) output.add("")
            }
            else -> output.add(segment)
        }
    }
    return output.joinToString("/") + suffix
}
