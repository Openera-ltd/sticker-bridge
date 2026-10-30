package tech.openera.stickerbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.openera.stickerbridge.ui.MainActivity

class Notifier(private val context: Context) {
    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STICKERS, "New stickers", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW))
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(tab: String): PendingIntent = PendingIntent.getActivity(
        context, tab.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun newStickers(count: Int) {
        if (!allowed() || count <= 0) return
        val text = if (count == 1) "1 new sticker is in your pack" else "$count new stickers are in your pack"
        val n = NotificationCompat.Builder(context, CH_STICKERS)
            .setSmallIcon(R.drawable.ic_stat_sticker)
            .setContentTitle("New stickers")
            .setContentText(text)
            .setContentIntent(openApp("stickers"))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_STICKERS, n) }
    }

    fun updateAvailable(versionName: String) {
        if (!allowed()) return
        val n = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_sticker)
            .setContentTitle("Update available")
            .setContentText("Sticker Bridge $versionName is ready to install")
            .setContentIntent(openApp("settings"))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_UPDATE, n) }
    }

    companion object {
        const val CH_STICKERS = "stickers"
        const val CH_UPDATES = "updates"
        private const val ID_STICKERS = 1
        private const val ID_UPDATE = 2
    }
}
