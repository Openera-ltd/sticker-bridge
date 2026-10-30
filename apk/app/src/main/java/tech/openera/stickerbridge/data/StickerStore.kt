package tech.openera.stickerbridge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class StoreState(
    val stickers: List<LocalSticker> = emptyList(),
    val versions: Map<String, Int> = emptyMap(),
    val fingerprints: Map<String, String> = emptyMap(),
)

/**
 * Stickers on this phone: files in one folder plus a small JSON index.
 * Read by the UI (as a flow) and by WhatsApp (through StickerContentProvider).
 */
class StickerStore(val dir: File) {
    private val index = File(dir, "index.json")
    private val lock = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    init {
        dir.mkdirs()
    }

    private fun load(): StoreState = try {
        if (index.exists()) ApiJson.decodeFromString(StoreState.serializer(), index.readText()) else StoreState()
    } catch (e: Exception) {
        StoreState()
    }

    /** Re-reads the index from disk (another process, e.g. WhatsApp's provider call, may need fresh data). */
    fun reload(): StoreState = load().also { _state.value = it }

    fun file(name: String): File = File(dir, name)

    fun packs(baseName: String): List<Pack> = PackBuilder.build(_state.value.stickers, baseName, _state.value.versions)

    suspend fun mutate(baseName: String, outline: Boolean, block: (List<LocalSticker>) -> List<LocalSticker>): StoreState =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val cur = _state.value
                val stickers = block(cur.stickers)
                val next = bumpVersions(cur.copy(stickers = stickers), baseName, outline)
                write(next)
                next
            }
        }

    /** Raises a pack's version whenever what WhatsApp would see changes, so WhatsApp refreshes it. */
    private fun bumpVersions(s: StoreState, baseName: String, outline: Boolean): StoreState {
        val versions = s.versions.toMutableMap()
        val prints = s.fingerprints.toMutableMap()
        PackBuilder.build(s.stickers, baseName, versions).forEach { p ->
            val fp = PackBuilder.fingerprint(p, outline)
            val old = prints[p.id]
            if (old == null) {
                versions.putIfAbsent(p.id, 1)
            } else if (old != fp) {
                versions[p.id] = (versions[p.id] ?: 1) + 1
            }
            prints[p.id] = fp
        }
        return s.copy(versions = versions, fingerprints = prints)
    }

    private fun write(s: StoreState) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(ApiJson.encodeToString(StoreState.serializer(), s))
        if (!tmp.renameTo(index)) {
            index.delete(); tmp.renameTo(index)
        }
        _state.value = s
    }

    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() }
            write(StoreState())
        }
    }
}
