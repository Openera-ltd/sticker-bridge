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
import kotlin.random.Random

@Serializable
data class StoreState(
    val stickers: List<LocalSticker> = emptyList(),
    /** Packs the user made. */
    val packs: List<UserPack> = emptyList(),
    /** True once the old automatic packs have been turned into user packs. */
    val packsMigrated: Boolean = false,
    val versions: Map<String, Int> = emptyMap(),
    val fingerprints: Map<String, String> = emptyMap(),
)

/**
 * Stickers and packs on this phone: files in one folder plus a small JSON index.
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

    /** The user's packs with their stickers, in the order they were made. */
    fun packs(): List<Pack> = resolve(_state.value)

    private fun resolve(s: StoreState): List<Pack> {
        val byId = s.stickers.associateBy { it.id }
        return s.packs.map { p ->
            Pack(p.id, UserPack.displayName(p.name), p.animated, p.stickerIds.mapNotNull { byId[it] }, s.versions[p.id] ?: 1)
        }
    }

    /** Changes the whole state at once; pack versions go up wherever WhatsApp would see a difference. */
    suspend fun update(outline: Boolean, block: (StoreState) -> StoreState): StoreState =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val next = bumpVersions(clean(block(_state.value)), outline)
                write(next)
                next
            }
        }

    /** Changes just the sticker list (packs drop stickers that are gone). */
    suspend fun mutate(outline: Boolean, block: (List<LocalSticker>) -> List<LocalSticker>): StoreState =
        update(outline) { it.copy(stickers = block(it.stickers)) }

    /** Packs never point at stickers that no longer exist, nor mix still and animated. */
    private fun clean(s: StoreState): StoreState {
        val byId = s.stickers.associateBy { it.id }
        return s.copy(packs = s.packs.map { p ->
            p.copy(stickerIds = p.stickerIds.distinct().filter { id -> byId[id]?.animated == p.animated }.take(Pack.MAX))
        })
    }

    private fun bumpVersions(s: StoreState, outline: Boolean): StoreState {
        val versions = s.versions.toMutableMap()
        val prints = s.fingerprints.toMutableMap()
        resolve(s).forEach { p ->
            val fp = PackBuilder.fingerprint(p, outline)
            val old = prints[p.id]
            if (old == null) versions.putIfAbsent(p.id, 1) else if (old != fp) versions[p.id] = (versions[p.id] ?: 1) + 1
            prints[p.id] = fp
        }
        return s.copy(versions = versions, fingerprints = prints)
    }

    // ---------- packs ----------

    /**
     * Makes packs from the chosen stickers. Still and animated go in separate packs (WhatsApp's rule),
     * and more than 30 spill into "<name> 2", "<name> 3"... Returns the new pack ids.
     */
    suspend fun createPack(name: String, stickerIds: List<String>, outline: Boolean, now: Long = System.currentTimeMillis()): List<String> {
        val made = mutableListOf<String>()
        update(outline) { s ->
            val byId = s.stickers.associateBy { it.id }
            val chosen = stickerIds.distinct().mapNotNull { byId[it] }
            val groups = chosen.partition { !it.animated }.toList().filter { it.isNotEmpty() }
            val mixed = groups.size > 1
            val packs = groups.flatMap { group ->
                group.chunked(Pack.MAX).mapIndexed { i, chunk ->
                    val animated = chunk.first().animated
                    val label = buildString {
                        append(name.trim())
                        if (mixed && animated) append(" Animated")
                        if (i > 0) append(" ").append(i + 1)
                    }
                    UserPack(newPackId(), label.take(60), animated, chunk.map { it.id }, now).also { made += it.id }
                }
            }
            s.copy(packs = s.packs + packs)
        }
        return made
    }

    /** Adds stickers to a pack (only the matching kind, up to 30). Returns how many were added. */
    suspend fun addToPack(packId: String, stickerIds: List<String>, outline: Boolean): Int {
        var added = 0
        update(outline) { s ->
            val byId = s.stickers.associateBy { it.id }
            s.copy(packs = s.packs.map { p ->
                if (p.id != packId) p else {
                    val fits = stickerIds.filter { it !in p.stickerIds && byId[it]?.animated == p.animated }
                    val next = (p.stickerIds + fits).take(Pack.MAX)
                    added = next.size - p.stickerIds.size
                    p.copy(stickerIds = next)
                }
            })
        }
        return added
    }

    suspend fun removeFromPack(packId: String, stickerId: String, outline: Boolean) =
        update(outline) { s -> s.copy(packs = s.packs.map { if (it.id == packId) it.copy(stickerIds = it.stickerIds - stickerId) else it }) }

    suspend fun renamePack(packId: String, name: String, outline: Boolean) =
        update(outline) { s -> s.copy(packs = s.packs.map { if (it.id == packId) it.copy(name = name.trim().take(60)) else it }) }

    suspend fun deletePack(packId: String, outline: Boolean) =
        update(outline) { s -> s.copy(packs = s.packs.filter { it.id != packId }) }

    /**
     * Before user packs existed, packs were made automatically ("static-1", "animated-1", ...).
     * Turns those into user packs with the same ids, so packs already in WhatsApp keep working.
     */
    suspend fun migrateAutoPacks(oldBaseName: String, outline: Boolean) = update(outline) { s ->
        if (s.packsMigrated) s
        else s.copy(
            packsMigrated = true,
            packs = s.packs + PackBuilder.build(s.stickers, oldBaseName, s.versions)
                .filter { auto -> s.packs.none { it.id == auto.id } }
                .map { UserPack(it.id, if (it.animated) "$oldBaseName Animated" else oldBaseName, it.animated, it.stickers.map { st -> st.id }) },
        )
    }

    private fun newPackId(): String = "p" + List(10) { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

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
            write(StoreState(packsMigrated = true))
        }
    }
}
