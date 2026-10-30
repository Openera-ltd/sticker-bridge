package tech.openera.stickerbridge.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.openera.stickerbridge.Graph
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.ui.MainActivity
import tech.openera.stickerbridge.whatsapp.WhatsApp

/**
 * Checks for new stickers every 15 seconds in the background, quietly.
 * Android only allows continuous background work with a notification, so it shows the
 * quietest one possible (minimum priority, silent, no badge). WorkManager stays as a safety net.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type)
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val g = Graph.get(applicationContext)
        while (scope.isActive) {
            val prefs = g.settings.current()
            val connected = g.settings.currentSession().connected
            if (!prefs.autoSync || !connected) break
            if (!prefs.wifiOnly || onWifi()) {
                val r = runCatching { g.sync.sync(quiet = true) }.getOrNull()
                if (r != null && r.added > 0) {
                    WhatsApp.notifyChanged(applicationContext)
                    if (prefs.notifyNewStickers) g.notifier.newStickers(r.added)
                }
                if (r?.signedOut == true) break
            }
            delay(INTERVAL_MS)
        }
        stopSelf()
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val INTERVAL_MS = 15_000L
        private const val CHANNEL = "background_sync"
        private const val NOTIFICATION_ID = 10

        fun start(context: Context) {
            // Android may refuse to start it while the app is in the background; WorkManager covers that.
            runCatching { context.startForegroundService(Intent(context, SyncService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }

        private fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL, "Background sync", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private fun notification(context: Context): Notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sticker)
                .setContentTitle("Sticker Bridge")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
    }
}
