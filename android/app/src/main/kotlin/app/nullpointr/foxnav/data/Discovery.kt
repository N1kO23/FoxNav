package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Turns what the user typed into a base URL: a bare domain means HTTPS. */
fun normalizeServerUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim().trimEnd('/')
    return if ("://" in trimmed) trimmed else "https://$trimmed"
}

/** Fetches a server's discovery document and resolves its URLs. */
suspend fun fetchDiscovery(serverUrl: String): DiscoveryDocument = withContext(Dispatchers.IO) {
    val documentUrl = normalizeServerUrl(serverUrl) + DiscoveryDocument.PATH
    val connection = URI(documentUrl).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode} from $documentUrl" }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        DiscoveryDocument.parse(body).resolved(documentUrl)
    } finally {
        connection.disconnect()
    }
}
