package app.nullpointr.foxnav.data

import android.content.Context
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import java.io.File
import java.security.MessageDigest

/**
 * The last discovery document each server sent, so FoxNav still starts without a connection:
 * the map then comes from downloaded areas and the cache, and the data packs are on the phone.
 */
class DiscoveryCache(context: Context) {
    private val directory = File(context.filesDir, "discovery")

    fun save(serverUrl: String, document: DiscoveryDocument) {
        runCatching {
            directory.mkdirs()
            val target = file(serverUrl)
            val temporary = File(directory, target.name + ".part")
            temporary.writeText(document.toJson())
            temporary.renameTo(target)
        }
    }

    /** The saved document, already resolved, or null. */
    fun load(serverUrl: String): DiscoveryDocument? =
        file(serverUrl).takeIf { it.exists() }?.let { runCatching { DiscoveryDocument.parse(it.readText()) }.getOrNull() }

    private fun file(serverUrl: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(normalizeServerUrl(serverUrl).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, hash.take(32) + ".json")
    }
}
