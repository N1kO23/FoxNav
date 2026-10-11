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

    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _online.value = capabilities.hasInternet()
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    private fun NetworkCapabilities?.hasInternet() = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
