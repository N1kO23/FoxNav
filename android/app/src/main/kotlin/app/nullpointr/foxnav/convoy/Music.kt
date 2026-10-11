package app.nullpointr.foxnav.convoy

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Here only so that Android lets FoxNav see which music apps are playing what: media sessions
 * are open to apps with notification access, and this is how an app asks for it. It reads no
 * notifications.
 */
class NowPlayingListener : NotificationListenerService()

/** How playing along went. */
enum class PlayAlong {
    /** Through the music app's own session, without leaving FoxNav: it can follow the next song too. */
    QUIET,

    /** A music app opened to play it. */
    OPENED,

    /** No music app here takes a song to play. */
    FAILED,
}

/** What the phone's music apps are playing: shared with the convoy if the driver wants, and played along to. */
class MusicSessions(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NowPlayingListener::class.java)

    /** Whether the driver gave FoxNav notification access, without which Android keeps the music apps' sessions private. */
    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Opens the system page where the driver gives it. */
    fun requestAccess() {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The song playing now, as it changes; null while nothing plays, and without access. */
    fun nowPlaying(): Flow<NowPlaying?> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        var controllers = emptyList<MediaController>()
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                trySend(current(controllers))
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                trySend(current(controllers))
            }
        }
        val sessions = MediaSessionManager.OnActiveSessionsChangedListener { active ->
            controllers.forEach { it.unregisterCallback(callback) }
            controllers = active.orEmpty()
            controllers.forEach { it.registerCallback(callback, handler) }
            trySend(current(controllers))
        }
        val listening = try {
            manager.addOnActiveSessionsChangedListener(sessions, listener, handler)
            sessions.onActiveSessionsChanged(manager.getActiveSessions(listener))
            true
        } catch (_: SecurityException) {
            // No access (yet): nothing to share.
            trySend(null)
            false
        }
        awaitClose {
            if (listening) manager.removeOnActiveSessionsChangedListener(sessions)
            controllers.forEach { it.unregisterCallback(callback) }
        }
    }.distinctUntilChanged()

    /**
     * Plays [track] in the driver's own music: quietly through a music app's session where
     * FoxNav can reach one, otherwise by asking a music app to search for it (which opens it).
     */
    fun playAlong(track: NowPlaying): PlayAlong {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, track.title)
            if (track.artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
        }
        val session = if (hasAccess()) {
            runCatching { manager.getActiveSessions(listener) }.getOrNull()
                ?.firstOrNull { (it.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L }
        } else {
            null
        }
        if (session != null) {
            session.transportControls.playFromSearch(query, extras)
            return PlayAlong.QUIET
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtras(extras)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(intent) }.isSuccess) PlayAlong.OPENED else PlayAlong.FAILED
    }

    private fun current(controllers: List<MediaController>): NowPlaying? {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName != context.packageName } ?: return null
        val metadata = playing.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty().trim()
        return NowPlaying(title = title.take(MAX_TEXT), artist = artist.take(MAX_TEXT), app = appName(playing.packageName).take(MAX_TEXT))
    }

    private fun appName(packageName: String): String = runCatching {
        val packages = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getApplicationInfo(packageName, 0)
        }
        packages.getApplicationLabel(info).toString()
    }.getOrDefault("")

    private companion object {
        const val MAX_TEXT = 120
    }
}
