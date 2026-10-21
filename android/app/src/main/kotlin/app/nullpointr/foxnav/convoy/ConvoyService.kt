package app.nullpointr.foxnav.convoy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.MainActivity
import app.nullpointr.foxnav.R
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.shared.convoy.ConvoyStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps the app running while in a convoy, so our position keeps going out with the screen off,
 * and shows a notification with a way to leave. It stops itself when the convoy ends.
 */
class ConvoyService : Service() {
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Convoy", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you're sharing your position with a convoy"
            },
        )
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Connecting..."), type)
        } catch (e: Exception) {
            // Android refuses a location service without the permission; the convoy then lasts while the app is open.
            Log.w(TAG, "Couldn't keep the convoy running in the background", e)
            stopSelf()
            return
        }

        val convoy = appGraph.convoy
        scope.launch {
            convoy.active
                .flatMapLatest { active ->
                    val session = active?.session ?: return@flatMapLatest flowOf(null)
                    combine(session.status, session.members) { status, members -> describe(status, members.size) }
                }
                .distinctUntilChanged()
                .collect { text -> if (text == null) stopSelf() else notifications.notify(NOTIFICATION_ID, notification(text)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) scope.launch { appGraph.convoy.leave() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(status: ConvoyStatus, others: Int): String = when (status) {
        ConvoyStatus.Connecting -> "Connecting..."
        is ConvoyStatus.Live -> when (others) {
            0 -> "Sharing your position · waiting for others"
            1 -> "Sharing your position with 1 driver"
            else -> "Sharing your position with $others drivers"
        }
        is ConvoyStatus.Reconnecting -> "Connection lost · reconnecting"
        is ConvoyStatus.Ended -> "Convoy ended"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val leave = PendingIntent.getService(this, 0, Intent(this, ConvoyService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("In a convoy")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Leave convoy", leave)
            .build()
    }

    companion object {
        private const val TAG = "FoxNavConvoy"
        private const val CHANNEL_ID = "convoy"
        private const val NOTIFICATION_ID = 0x0C0F
        private const val ACTION_LEAVE = "app.nullpointr.foxnav.convoy.LEAVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConvoyService::class.java))
        }
    }
}
