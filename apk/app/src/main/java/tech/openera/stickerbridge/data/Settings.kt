package tech.openera.stickerbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val autoSync: Boolean = true,
    /** Minutes between background checks. Android's minimum is 15. */
    val syncIntervalMin: Int = 5,
    val wifiOnly: Boolean = false,
    val notifyNewStickers: Boolean = true,
    val outline: Boolean = false,
    val packName: String = DEFAULT_PACK_NAME,
    val defaultEmoji: String = "😀",
    val theme: ThemeMode = ThemeMode.System,
    val wallpaperColors: Boolean = false,
    val autoUpdateCheck: Boolean = true,
) {
    companion object {
        const val DEFAULT_PACK_NAME = "My TikTok Stickers"
        val INTERVALS = listOf(15)
    }
}

data class Session(
    val token: String? = null,
    val accountId: String? = null,
    val username: String? = null,
    val cursor: String? = null,
    val lastSyncAt: Long = 0,
    val installId: String = "",
) {
    val connected: Boolean get() = token != null
}

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepo(private val context: Context) {
    private object K {
        val autoSync = booleanPreferencesKey("auto_sync")
        val interval = intPreferencesKey("sync_interval")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val notify = booleanPreferencesKey("notify")
        val outline = booleanPreferencesKey("outline")
        val outlineDefaultChecked = booleanPreferencesKey("outline_default_102")
        val packName = stringPreferencesKey("pack_name")
        val emoji = stringPreferencesKey("default_emoji")
        val theme = stringPreferencesKey("theme")
        val wallpaper = booleanPreferencesKey("wallpaper_colors")
        val updates = booleanPreferencesKey("auto_update_check")

        val token = stringPreferencesKey("token")
        val accountId = stringPreferencesKey("account_id")
        val username = stringPreferencesKey("username")
        val cursor = stringPreferencesKey("cursor")
        val lastSync = longPreferencesKey("last_sync")
        val installId = stringPreferencesKey("install_id")
    }

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            autoSync = p[K.autoSync] ?: true,
            syncIntervalMin = p[K.interval] ?: 5,
            wifiOnly = p[K.wifiOnly] ?: false,
            notifyNewStickers = p[K.notify] ?: true,
            outline = p[K.outline] ?: false,
            packName = p[K.packName] ?: AppSettings.DEFAULT_PACK_NAME,
            defaultEmoji = p[K.emoji] ?: "😀",
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            wallpaperColors = p[K.wallpaper] ?: false,
            autoUpdateCheck = p[K.updates] ?: true,
        )
    }

    val session: Flow<Session> = context.store.data.map { p ->
        Session(
            token = p[K.token],
            accountId = p[K.accountId],
            username = p[K.username],
            cursor = p[K.cursor],
            lastSyncAt = p[K.lastSync] ?: 0,
            installId = p[K.installId] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentSession(): Session = session.first()

    suspend fun installId(): String {
        val existing = currentSession().installId
        if (existing.isNotEmpty()) return existing
        val id = UUID.randomUUID().toString()
        context.store.edit { it[K.installId] = id }
        return id
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val cur = AppSettings(
                autoSync = p[K.autoSync] ?: true,
                syncIntervalMin = p[K.interval] ?: 5,
                wifiOnly = p[K.wifiOnly] ?: false,
                notifyNewStickers = p[K.notify] ?: true,
                outline = p[K.outline] ?: false,
                packName = p[K.packName] ?: AppSettings.DEFAULT_PACK_NAME,
                defaultEmoji = p[K.emoji] ?: "😀",
                theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
                wallpaperColors = p[K.wallpaper] ?: false,
                autoUpdateCheck = p[K.updates] ?: true,
            )
            val n = block(cur)
            p[K.autoSync] = n.autoSync
            p[K.interval] = n.syncIntervalMin.coerceAtLeast(1)
            p[K.wifiOnly] = n.wifiOnly
            p[K.notify] = n.notifyNewStickers
            p[K.outline] = n.outline
            p[K.packName] = n.packName.trim().take(100).ifEmpty { AppSettings.DEFAULT_PACK_NAME }
            p[K.emoji] = n.defaultEmoji
            p[K.theme] = n.theme.name
            p[K.wallpaper] = n.wallpaperColors
            p[K.updates] = n.autoUpdateCheck
        }
    }

    suspend fun saveLink(token: String, account: Account) {
        context.store.edit {
            it[K.token] = token
            it[K.accountId] = account.id
            it[K.username] = account.username
            it.remove(K.cursor)
        }
    }

    suspend fun saveSync(cursor: String?, at: Long) {
        context.store.edit {
            if (cursor != null) it[K.cursor] = cursor
            it[K.lastSync] = at
        }
    }

    /**
     * 1.0.2 turned the white outline off by default. Returns true once if this install never chose
     * a setting, so stickers made with the old default can be redone without the outline.
     */
    suspend fun outlineDefaultChanged(): Boolean {
        var changed = false
        context.store.edit { p ->
            if (p[K.outlineDefaultChecked] != true) {
                p[K.outlineDefaultChecked] = true
                changed = p[K.outline] == null
            }
        }
        return changed
    }

    suspend fun clearSession() {
        context.store.edit {
            it.remove(K.token); it.remove(K.accountId); it.remove(K.username)
            it.remove(K.cursor); it.remove(K.lastSync)
        }
    }
}
