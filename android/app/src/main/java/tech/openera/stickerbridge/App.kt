package tech.openera.stickerbridge

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.openera.stickerbridge.sync.SyncScheduler

class App : Application(), Configuration.Provider {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        val g = Graph.get(this)
        g.notifier.createChannels()
        scope.launch { SyncScheduler.apply(this@App, g.settings.current(), g.settings.currentSession().connected) }
    }
}
