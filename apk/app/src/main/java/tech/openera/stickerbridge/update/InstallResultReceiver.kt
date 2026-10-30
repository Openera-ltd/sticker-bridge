package tech.openera.stickerbridge.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import tech.openera.stickerbridge.Graph

/** Android reports install progress here; we open its "Install update?" screen when asked. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
        Graph.get(context).updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
