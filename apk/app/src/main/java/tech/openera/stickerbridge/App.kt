package tech.openera.stickerbridge

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.openera.stickerbridge.sync.SyncScheduler

class App : Application(), Configuration.Provider, coil.ImageLoaderFactory {
    /** Plays animated stickers (animated WebP and GIF) instead of showing their first frame. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .components {
            // The Robolectric test runtime crashes in Android's ImageDecoder, so tests use the plain decoder.
            if (android.os.Build.FINGERPRINT == "robolectric") return@components
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
            else add(coil.decode.GifDecoder.Factory())
        }
        .crossfade(true)
        .build()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        val g = Graph.get(this)
        g.notifier.createChannels()
        scope.launch { SyncScheduler.apply(this@App, g.settings.current(), g.settings.currentSession().connected) }
        // Packs from earlier versions become user packs (same ids, so WhatsApp keeps them).
        scope.launch { g.sync.migratePacks() }
        // Stickers made with the old outline default are redone once, without it.
        scope.launch {
            if (g.settings.outlineDefaultChanged() && g.store.state.value.stickers.isNotEmpty()) {
                g.sync.reprocessAll()
                tech.openera.stickerbridge.whatsapp.WhatsApp.notifyChanged(this@App)
            }
        }
    }
}
