package app.nullpointr.foxnav.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * From Android 17, apps targeting API 37 can't reach local-network addresses (private,
 * link-local and `.local` names) without this runtime permission ("Nearby devices"). Self-hosted
 * servers at home and the emulator's host (10.0.2.2) are such addresses.
 */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Whether this Android version enforces local network protection. */
val localNetworkPermissionRequired: Boolean
    get() = Build.VERSION.SDK_INT >= 37

/** Whether [serverUrl] (as the user typed it) points at a server on the local network. */
suspend fun isOnLocalNetwork(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
    val host = URI(normalizeServerUrl(serverUrl)).host ?: return@withContext false
    if (host.endsWith(".local", ignoreCase = true)) return@withContext true
    val addresses = runCatching { InetAddress.getAllByName(host) }.getOrElse { return@withContext false }
    addresses.any { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isUniqueLocalIpv6() }
}

/** fc00::/7, the IPv6 counterpart of private IPv4 ranges. */
private fun InetAddress.isUniqueLocalIpv6(): Boolean = this is Inet6Address && (address[0].toInt() and 0xFE) == 0xFC
