package tech.openera.stickerbridge

import android.content.Context
import android.os.Build
import tech.openera.stickerbridge.data.DemoApi
import tech.openera.stickerbridge.data.HttpApi
import tech.openera.stickerbridge.data.SettingsRepo
import tech.openera.stickerbridge.data.StickerApi
import tech.openera.stickerbridge.data.StickerStore
import tech.openera.stickerbridge.data.SyncRepository
import tech.openera.stickerbridge.update.UpdateManager
import java.io.File

/** The app's shared objects, created once. Safe to call from the provider before App.onCreate. */
class Graph private constructor(context: Context) {
    val app: Context = context.applicationContext
    val settings = SettingsRepo(app)
    val store = StickerStore(File(app.filesDir, "stickers"))
    val api: StickerApi = if (BuildConfig.DEMO) DemoApi() else HttpApi(
        BuildConfig.API_BASE,
        "StickerBridge/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})",
    )
    val sync = SyncRepository(api, settings, store)
    val updates = UpdateManager(app, api)
    val notifier = Notifier(app)

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }

        /** Tests only: start from a fresh graph. */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() {
            instance = null
        }
    }
}
