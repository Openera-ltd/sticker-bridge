package tech.openera.stickerbridge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class SyncResult(
    val added: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    val signedOut: Boolean = false,
)

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val result: SyncResult, val at: Long) : SyncStatus
}

/**
 * Pulls new stickers from the server, converts them, and keeps WhatsApp packs up to date.
 * Called by the background worker, pull-to-refresh, and when the app opens.
 */
class SyncRepository(
    private val api: StickerApi,
    private val settings: SettingsRepo,
    private val store: StickerStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * [quiet] = the frequent background check while the app is open: no spinner, and errors stay
     * silent (it tries again in a few seconds). The result only shows if something arrived.
     */
    suspend fun sync(quiet: Boolean = false): SyncResult = lock.withLock {
        if (!quiet) _status.value = SyncStatus.Running
        val result = runCatching { doSync() }.getOrElse { e ->
            when {
                e is ApiException && e.code == 401 -> {
                    settings.clearSession()
                    SyncResult(error = "Your TikTok connection ended. Connect again.", signedOut = true)
                }
                // Offline or timeouts: normal on phones, not worth reporting.
                e is java.io.IOException && e !is ApiException -> SyncResult(error = "Couldn't sync. Check your connection.")
                else -> {
                    // Technical details go to Sentry only; people see a plain message.
                    io.sentry.Sentry.captureException(e)
                    SyncResult(error = "Couldn't sync right now. Try again soon.")
                }
            }
        }
        if (!quiet || result.added > 0 || result.signedOut) _status.value = SyncStatus.Done(result, clock())
        result
    }

    private suspend fun doSync(): SyncResult = withContext(Dispatchers.IO) {
        val session = settings.currentSession()
        val token = session.token ?: return@withContext SyncResult(error = "Not connected")
        val prefs = settings.current()
        val known: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(store.state.value.stickers.map { it.id }) }
        var cursor = session.cursor
        val added = java.util.concurrent.atomic.AtomicInteger()
        val skipped = java.util.concurrent.atomic.AtomicInteger()
        val parallel = kotlinx.coroutines.sync.Semaphore(PARALLEL)

        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = api.stickers(token, cursor)
            val todo = page.items.distinctBy { it.id }.filter { it.id !in known }
            // A few at a time, and each sticker shows up the moment it's ready (not after the whole batch).
            kotlinx.coroutines.coroutineScope {
                todo.forEach { item ->
                    launch {
                        parallel.withPermit {
                            val saved = runCatching { save(token, item, prefs.outline, prefs.defaultEmoji) }
                                .onFailure { e ->
                                    // A sticker that couldn't be converted: report it so formats can be supported.
                                    if (e !is java.io.IOException) io.sentry.Sentry.captureException(e) { it.setTag("sticker_id", item.id) }
                                }
                                .getOrNull()
                            if (saved == null) {
                                skipped.incrementAndGet()
                            } else {
                                val old = item.replaces?.let { r -> store.state.value.stickers.firstOrNull { it.id == r } }
                                if (old != null) {
                                    // A re-made sticker (e.g. now animated) takes the old one's place, in packs too.
                                    store.update(prefs.outline) { st ->
                                        st.copy(
                                            stickers = st.stickers.map { if (it.id == old.id) saved.copy(addedAt = old.addedAt) else it },
                                            packs = st.packs.map { pk -> pk.copy(stickerIds = pk.stickerIds.map { if (it == old.id) saved.id else it }) },
                                        )
                                    }
                                    if (old.fileName != saved.fileName) store.file(old.fileName).delete()
                                    store.file("orig_${old.id}").delete()
                                } else {
                                    store.mutate(prefs.outline) { list -> if (list.any { it.id == saved.id }) list else list + saved }
                                }
                                known += saved.id
                                added.incrementAndGet()
                            }
                        }
                    }
                }
            }
            if (todo.isNotEmpty()) refreshTrays()
            val next = page.cursor
            val done = page.items.isEmpty() || next == null || next == cursor
            if (next != null) cursor = next
            settings.saveSync(cursor, clock()) // progress is kept even if the phone stops us mid-way
            if (done) break
        }

        settings.saveSync(cursor, clock())
        SyncResult(added = added.get(), skipped = skipped.get())
    }

    private suspend fun save(token: String, item: RemoteSticker, outline: Boolean, defaultEmoji: String): LocalSticker? {
        val bytes = api.download(token, item.file)
        val kind = StickerProcessor.detect(bytes)
        val safeId = item.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        if (safeId.isEmpty()) return null
        val emojis = item.emojis.take(3).ifEmpty { listOf(defaultEmoji) }
        val animated = item.animated || kind == StickerProcessor.Kind.AnimatedWebp || kind == StickerProcessor.Kind.Gif
        return if (animated && StickerProcessor.isValidAnimated(bytes)) {
            // Already in WhatsApp's animated format: keep it as is.
            store.file("$safeId.webp").writeBytes(bytes)
            LocalSticker(safeId, "$safeId.webp", animated = true, emojis = emojis, addedAt = clock())
        } else {
            // Still images, and animated ones WhatsApp wouldn't take (TikTok's are a different size):
            // a still sticker from the (first) frame, so it still lands in the pack.
            store.file("orig_$safeId").writeBytes(bytes) // kept so the outline can be redone later
            store.file("$safeId.webp").writeBytes(StickerProcessor.toStaticSticker(bytes, outline))
            LocalSticker(safeId, "$safeId.webp", animated = false, emojis = emojis, addedAt = clock())
        }
    }

    /** Re-renders every still sticker, e.g. after the outline setting changes. */
    suspend fun reprocessAll() = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        store.state.value.stickers.filter { !it.animated }.forEach { s ->
            val orig = store.file("orig_${s.id}")
            if (orig.exists()) runCatching {
                store.file(s.fileName).writeBytes(StickerProcessor.toStaticSticker(orig.readBytes(), prefs.outline))
            }
        }
        store.mutate(prefs.outline) { it }
        refreshTrays()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        val s = store.state.value.stickers.firstOrNull { it.id == id } ?: return@withContext
        store.mutate(prefs.outline) { list -> list.filter { it.id != id } }
        store.file(s.fileName).delete()
        store.file("orig_$id").delete()
        refreshTrays()
    }

    suspend fun markAllSeen() {
        val prefs = settings.current()
        if (store.state.value.stickers.none { it.isNew }) return
        store.mutate(prefs.outline) { list -> list.map { it.copy(isNew = false) } }
    }

    // ---------- packs the user makes ----------

    suspend fun createPack(name: String, stickerIds: List<String>): List<String> {
        val ids = store.createPack(name, stickerIds, settings.current().outline)
        refreshTrays()
        return ids
    }

    suspend fun addToPack(packId: String, stickerIds: List<String>): Int =
        store.addToPack(packId, stickerIds, settings.current().outline).also { refreshTrays() }

    suspend fun removeFromPack(packId: String, stickerId: String) {
        store.removeFromPack(packId, stickerId, settings.current().outline)
        refreshTrays()
    }

    suspend fun renamePack(packId: String, name: String) {
        store.renamePack(packId, name, settings.current().outline)
    }

    suspend fun deletePack(packId: String) {
        store.deletePack(packId, settings.current().outline)
        store.file("tray_$packId.png").delete()
    }

    /** One-time: the automatic packs from earlier versions become user packs (same ids, so WhatsApp keeps them). */
    suspend fun migratePacks() {
        if (store.state.value.packsMigrated) return
        val prefs = settings.current()
        store.migrateAutoPacks(prefs.packName, prefs.outline)
        refreshTrays()
    }

    suspend fun refreshTrays() = withContext(Dispatchers.IO) {
        store.packs().forEach { p ->
            if (p.stickers.isEmpty()) return@forEach
            // WhatsApp needs a tray icon: use the first sticker that can be read, else a blank one.
            val png = p.stickers.asSequence()
                .mapNotNull { s -> runCatching { StickerProcessor.tray(store.file(s.fileName).readBytes()) }.getOrNull() }
                .firstOrNull() ?: StickerProcessor.blankTray()
            runCatching { store.file(p.trayFile).writeBytes(png) }
        }
    }

    suspend fun signOut(deleteServerData: Boolean) {
        val token = settings.currentSession().token
        if (token != null) runCatching {
            if (deleteServerData) api.deleteData(token) else api.unlink(token)
        }
        settings.clearSession()
        if (deleteServerData) store.clear()
        _status.value = SyncStatus.Idle
    }

    companion object {
        const val MAX_PAGES = 10
        /** Stickers downloaded and converted at the same time. */
        const val PARALLEL = 3
    }
}
