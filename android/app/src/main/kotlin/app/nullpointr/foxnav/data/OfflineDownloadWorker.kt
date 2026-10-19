package app.nullpointr.foxnav.data

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.nullpointr.foxnav.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps offline downloads going while the app is in the background or closed: road data
 * ([RoadData]) and map areas ([OfflineMaps]) download in the app's process, and a running worker
 * keeps that process alive. Within WorkManager's window, and then again until all is in; tiles
 * already downloaded stay, so each run picks up where the last one stopped.
 */
class OfflineDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val graph = context.appGraph

    override suspend fun doWork(): Result {
        // Woken with no screen open: road data needs the server, from its last discovery document.
        graph.discoveryCache.load(graph.serverSettings.serverUrl.value)?.let(graph.roadData::useUnlessFollowing)
        withContext(Dispatchers.Main) {
            val maps = graph.offlineMaps
            maps.configure()
            // MapLibre lists its packs a moment after starting. (Polled: with no screen, nothing sends Compose's snapshot notifications.)
            withTimeoutOrNull(5.seconds) { while (maps.areas.isEmpty()) delay(LIST_POLL_MS) }
            if (graph.offlineDownloads.allowed.value) maps.resumeUnfinished()
        }
        val until = SystemClock.elapsedRealtime() + RUN_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (!pending()) return Result.success()
            delay(CHECK_MS)
        }
        return if (pending()) Result.retry() else Result.success()
    }

    private suspend fun pending(): Boolean =
        graph.roadData.state.value.toDownload > 0 || withContext(Dispatchers.Main) { graph.offlineMaps.areas.any { it.status == AreaStatus.DOWNLOADING } }

    private companion object {
        /** Short of the ten minutes a worker gets. */
        const val RUN_MS = 9 * 60_000L
        const val CHECK_MS = 5_000L
        const val LIST_POLL_MS = 250L
    }
}
