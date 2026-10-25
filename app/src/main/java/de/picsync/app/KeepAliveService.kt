package de.picsync.app

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.picsync.app.WhatsAppSession.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process and its network access alive while pairing or syncing, e.g. while the
 * user switches to WhatsApp to enter the pairing code. Stops itself once nothing is running.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PicSyncApp
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(describe(app.session.status.value, app.sync.state.value) ?: "Aktiv"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!observing) {
            observing = true
            scope.launch {
                combine(app.session.status, app.sync.state, ::describe).collect { text ->
                    if (text == null) {
                        ServiceCompat.stopForeground(this@KeepAliveService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Notification text, or null if the service is no longer needed. */
    private fun describe(status: Status, sync: SyncState): String? = when {
        status is Status.Pairing -> status.code?.let { "Kopplungscode: $it" } ?: "Fordere Kopplungscode an…"
        status == Status.Connecting -> "Verbinde mit WhatsApp…"
        sync is SyncState.Loading -> if (sync.total > 0) "${sync.phase} (${sync.done}/${sync.total})" else sync.phase
        sync is SyncState.Applying -> "Fotos übernehmen (${sync.done}/${sync.total})"
        else -> null
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kopplung & Sync", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PicSync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        }
    }
}
