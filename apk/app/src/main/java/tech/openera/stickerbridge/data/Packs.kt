package tech.openera.stickerbridge.data

import kotlinx.serialization.Serializable

@Serializable
data class LocalSticker(
    val id: String,
    /** File name inside the stickers folder, e.g. "abc.webp". */
    val fileName: String,
    val animated: Boolean = false,
    val emojis: List<String> = emptyList(),
    val addedAt: Long = 0,
    val isNew: Boolean = true,
)

/** A pack the user made from stickers they picked. */
@Serializable
data class UserPack(
    /** Stable id WhatsApp knows the pack by. */
    val id: String,
    /** What the user typed; WhatsApp shows "Sticker Bridge · <name>". */
    val name: String,
    val animated: Boolean,
    val stickerIds: List<String>,
    val createdAt: Long = 0,
) {
    companion object {
        const val PREFIX = "Sticker Bridge"
        fun displayName(name: String): String =
            if (name.isBlank()) PREFIX else "$PREFIX · ${name.trim()}".take(128)
    }
}

data class Pack(
    /** Stable id WhatsApp knows the pack by, e.g. "static-1". */
    val id: String,
    val name: String,
    val animated: Boolean,
    val stickers: List<LocalSticker>,
    val version: Int,
) {
    val trayFile: String get() = "tray_$id.png"
    /** WhatsApp refuses packs with fewer than 3 stickers. */
    val ready: Boolean get() = stickers.size >= MIN
    val missing: Int get() = (MIN - stickers.size).coerceAtLeast(0)

    companion object {
        const val MIN = 3
        const val MAX = 30
    }
}

/**
 * Splits stickers into WhatsApp packs: still and animated never mix,
 * at most 30 per pack, oldest first so existing packs stay stable as new stickers arrive.
 */
object PackBuilder {
    fun build(stickers: List<LocalSticker>, baseName: String, versions: Map<String, Int> = emptyMap()): List<Pack> {
        val sorted = stickers.sortedWith(compareBy({ it.addedAt }, { it.id }))
        fun group(list: List<LocalSticker>, animated: Boolean): List<Pack> =
            list.chunked(Pack.MAX).mapIndexed { i, chunk ->
                val n = i + 1
                val id = (if (animated) "animated-" else "static-") + n
                val name = buildString {
                    append(baseName)
                    if (animated) append(" · Animated")
                    if (n > 1) append(" ").append(n)
                }.take(128)
                Pack(id, name, animated, chunk, versions[id] ?: 1)
            }
        return group(sorted.filter { !it.animated }, false) + group(sorted.filter { it.animated }, true)
    }

    /** A fingerprint of what WhatsApp sees; when it changes the pack's version must go up. */
    fun fingerprint(pack: Pack, outline: Boolean): String =
        buildString {
            append(pack.name).append('|').append(outline)
            pack.stickers.forEach { append('|').append(it.id).append(':').append(it.emojis.joinToString(",")) }
        }.hashCode().toString(16)
}
