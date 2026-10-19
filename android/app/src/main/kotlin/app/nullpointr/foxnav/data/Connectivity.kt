package app.nullpointr.foxnav.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has a network that can carry traffic. Not whether the server answers: a
 * local server works on Wi-Fi without internet, so that counts as online too.
 */
class Connectivity(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(manager.activeNetwork?.let(manager::getNetworkCapabilities).hasInternet())
    private val _unmetered = MutableStateFlow(manager.activeNetwork?.let(manager::getNetworkCapabilities).isUnmetered())

    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Whether the network doesn't charge by the byte: Wi-Fi, usually, for offline downloads. */
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _online.value = capabilities.hasInternet()
                _unmetered.value = capabilities.isUnmetered()
            }

            override fun onLost(network: Network) {
                _online.value = false
                _unmetered.value = false
            }
        })
    }

    private fun NetworkCapabilities?.hasInternet() = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun NetworkCapabilities?.isUnmetered() = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
}
