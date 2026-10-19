package app.nullpointr.foxnav.data

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit

/**
 * When offline data (map areas, road data) may download: with a connection, and only on Wi-Fi if
 * the driver chose so. Through WorkManager ([OfflineDownloadWorker]), downloads carry on with the
 * app in the background or closed, and after the phone restarts.
 */
class OfflineDownloads(private val context: Context, connectivity: Connectivity, scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("offline", Context.MODE_PRIVATE)
    private val _wifiOnly = MutableStateFlow(preferences.getBoolean(KEY_WIFI_ONLY, false))

    /** Whether downloads wait for an unmetered network. */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** Whether downloads may run now. */
    val allowed: StateFlow<Boolean> = combine(connectivity.online, connectivity.unmetered, wifiOnly) { online, unmetered, wifiOnly ->
        online && (unmetered || !wifiOnly)
    }.stateIn(scope, SharingStarted.Eagerly, connectivity.online.value && (connectivity.unmetered.value || wifiOnly.value))

    /** Whether downloads are waiting for Wi-Fi: connected, but on mobile data. */
    val waitingForWifi: StateFlow<Boolean> = combine(connectivity.online, connectivity.unmetered, wifiOnly) { online, unmetered, wifiOnly ->
        online && wifiOnly && !unmetered
    }.stateIn(scope, SharingStarted.Eagerly, false)

    fun setWifiOnly(on: Boolean) {
        preferences.edit { putBoolean(KEY_WIFI_ONLY, on) }
        _wifiOnly.value = on
        // The worker's network constraint changes with it.
        schedule()
    }

    /** Has WorkManager see what's left to download through, whenever the network allows. */
    fun schedule() {
        val network = if (_wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val KEY_WIFI_ONLY = "wifi_only"
        const val WORK = "offline-downloads"
        const val RETRY_SECONDS = 30L
    }
}
