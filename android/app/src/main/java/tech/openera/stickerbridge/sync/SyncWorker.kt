package tech.openera.stickerbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tech.openera.stickerbridge.Graph
import tech.openera.stickerbridge.data.AppSettings
import tech.openera.stickerbridge.whatsapp.WhatsApp
import java.util.concurrent.TimeUnit

/** Runs in the background: fetch new stickers, update packs, tell WhatsApp, and check for app updates. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = Graph.get(applicationContext)
        val prefs = g.settings.current()
        val result = g.sync.sync()
        if (result.added > 0) {
            WhatsApp.notifyChanged(applicationContext)
            if (prefs.notifyNewStickers) g.notifier.newStickers(result.added)
        }
        if (prefs.autoUpdateCheck) {
            val day = 24 * 60 * 60 * 1000L
            val last = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("last_check", 0)
            if (System.currentTimeMillis() - last > day) {
                applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply()
                g.updates.check()?.let { g.notifier.updateAvailable(it.versionName) }
            }
        }
        return when {
            result.signedOut -> Result.success()
            result.error != null -> Result.retry()
            else -> Result.success()
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "sticker-sync"
    private const val NOW = "sticker-sync-now"

    private fun constraints(s: AppSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Starts, changes or stops background sync to match the settings. */
    fun apply(context: Context, s: AppSettings, connected: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!s.autoSync || !connected) {
            wm.cancelUniqueWork(PERIODIC); return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(s.syncIntervalMin.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints(s))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(context: Context, s: AppSettings) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(s)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }
}
