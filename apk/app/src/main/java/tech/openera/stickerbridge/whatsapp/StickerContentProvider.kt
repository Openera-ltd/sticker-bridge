package tech.openera.stickerbridge.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import tech.openera.stickerbridge.Graph
import tech.openera.stickerbridge.data.Pack
import java.io.FileNotFoundException

/**
 * WhatsApp reads our packs through this provider (the contract from WhatsApp's sticker sample).
 * Only packs with 3+ stickers are shown. Files are served read-only and only if they belong to the pack.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var authority: String
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        authority = context!!.packageName + AUTHORITY_SUFFIX
        matcher.addURI(authority, METADATA, CODE_ALL)
        matcher.addURI(authority, "$METADATA/*", CODE_ONE)
        matcher.addURI(authority, "$STICKERS/*", CODE_STICKERS)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", CODE_ASSET)
        return true
    }

    private fun readyPacks(): List<Pack> {
        val g = Graph.get(context!!)
        val name = runBlocking { g.settings.current().packName }
        g.store.reload()
        return g.store.packs(name).filter { it.ready }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? =
        when (matcher.match(uri)) {
            CODE_ALL -> packCursor(uri, readyPacks())
            CODE_ONE -> packCursor(uri, readyPacks().filter { it.id == uri.lastPathSegment })
            CODE_STICKERS -> stickerCursor(uri, readyPacks().firstOrNull { it.id == uri.lastPathSegment })
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

    private fun packCursor(uri: Uri, packs: List<Pack>): Cursor {
        val c = MatrixCursor(PACK_COLUMNS)
        packs.forEach { p ->
            c.addRow(
                arrayOf<Any?>(
                    p.id, p.name, PUBLISHER, p.trayFile,
                    "", "", "", "", "", "",
                    p.version.toString(), 0, if (p.animated) 1 else 0,
                )
            )
        }
        c.setNotificationUri(context!!.contentResolver, uri)
        return c
    }

    private fun stickerCursor(uri: Uri, pack: Pack?): Cursor {
        val c = MatrixCursor(STICKER_COLUMNS)
        pack?.stickers?.forEach { s ->
            c.addRow(arrayOf<Any?>(s.fileName, s.emojis.joinToString(","), "Sticker ${s.emojis.joinToString(" ")}".take(125)))
        }
        c.setNotificationUri(context!!.contentResolver, uri)
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (matcher.match(uri) != CODE_ASSET || mode != "r") throw FileNotFoundException(uri.toString())
        val seg = uri.pathSegments
        val packId = seg[seg.size - 2]
        val fileName = seg.last()
        val pack = readyPacks().firstOrNull { it.id == packId } ?: throw FileNotFoundException("No pack $packId")
        val allowed = fileName == pack.trayFile || pack.stickers.any { it.fileName == fileName }
        if (!allowed) throw FileNotFoundException("Not in pack: $fileName")
        val file = Graph.get(context!!).store.file(fileName)
        if (!file.exists()) throw FileNotFoundException(fileName)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_ONE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_ASSET -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY_SUFFIX = ".stickercontentprovider"
        const val PUBLISHER = "Sticker Bridge"
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"
        private const val CODE_ALL = 1
        private const val CODE_ONE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_ASSET = 4

        val PACK_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
        )
        val STICKER_COLUMNS = arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")
    }
}
