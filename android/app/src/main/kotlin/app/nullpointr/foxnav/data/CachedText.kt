package app.nullpointr.foxnav.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Text files from a server, kept on the phone in [directory] and revalidated with their ETag:
 * fresh when the server answers, the stored copy when it doesn't.
 */
class CachedText(private val directory: File, private val client: OkHttpClient) {
    /** The copy on the phone, if there is one. */
    fun stored(url: String): String? = file(url, "json").takeIf { it.exists() }?.readText()

    /** The file's text: fresh, revalidated, or the copy we have when offline. Null if there's none. */
    fun fetch(url: String): String? {
        directory.mkdirs()
        val cached = file(url, "json")
        val etag = file(url, "etag")
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
                    // The server has no such file (any more).
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

    private fun file(url: String, extension: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return File(directory, "$name.$extension")
    }
}
