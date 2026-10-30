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
                                store.mutate(prefs.packName, prefs.outline) { list -> if (list.any { it.id == saved.id }) list else list + saved }
                                known += saved.id
                                added.incrementAndGet()
                            }
                        }
                    }
                }
            }
            if (todo.isNotEmpty()) refreshTrays(prefs.packName)
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
        store.mutate(prefs.packName, prefs.outline) { it }
        refreshTrays(prefs.packName)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        val s = store.state.value.stickers.firstOrNull { it.id == id } ?: return@withContext
        store.mutate(prefs.packName, prefs.outline) { list -> list.filter { it.id != id } }
        store.file(s.fileName).delete()
        store.file("orig_$id").delete()
        refreshTrays(prefs.packName)
    }

    suspend fun setEmojis(id: String, emojis: List<String>) {
        val prefs = settings.current()
        val clean = emojis.distinct().take(3).ifEmpty { listOf(prefs.defaultEmoji) }
        store.mutate(prefs.packName, prefs.outline) { list ->
            list.map { if (it.id == id) it.copy(emojis = clean) else it }
        }
    }

    suspend fun markAllSeen() {
        val prefs = settings.current()
        if (store.state.value.stickers.none { it.isNew }) return
        store.mutate(prefs.packName, prefs.outline) { list -> list.map { it.copy(isNew = false) } }
    }

    /** Pack name changed: versions go up so WhatsApp shows the new name. */
    suspend fun renamePacks() {
        val prefs = settings.current()
        store.mutate(prefs.packName, prefs.outline) { it }
        refreshTrays(prefs.packName)
    }

    suspend fun refreshTrays(baseName: String) = withContext(Dispatchers.IO) {
        store.packs(baseName).forEach { p ->
            val first = p.stickers.firstOrNull() ?: return@forEach
            runCatching {
                store.file(p.trayFile).writeBytes(StickerProcessor.tray(store.file(first.fileName).readBytes()))
            }
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
