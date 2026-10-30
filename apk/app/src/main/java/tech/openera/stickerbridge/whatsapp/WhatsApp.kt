package tech.openera.stickerbridge.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

enum class WaApp(val pkg: String, val label: String) {
    Consumer("com.whatsapp", "WhatsApp"),
    Business("com.whatsapp.w4b", "WhatsApp Business"),
}

/** Talking to WhatsApp: is it installed, is a pack already added, and the "add pack" request. */
object WhatsApp {
    private const val ADD_ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun authority(context: Context) = context.packageName + StickerContentProvider.AUTHORITY_SUFFIX

    fun installed(context: Context): List<WaApp> = WaApp.entries.filter { app ->
        try {
            context.packageManager.getPackageInfo(app.pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** true/false from WhatsApp itself; null if WhatsApp is missing or too old to say. */
    fun isAdded(context: Context, app: WaApp, packId: String): Boolean? = try {
        val uri = Uri.Builder().scheme("content")
            .authority("${app.pkg}.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", authority(context))
            .appendQueryParameter("identifier", packId)
            .build()
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow("result")) == 1 else null
        }
    } catch (e: Exception) {
        null
    }

    fun addIntent(context: Context, app: WaApp, packId: String, name: String): Intent =
        Intent(ADD_ACTION).apply {
            putExtra("sticker_pack_id", packId)
            putExtra("sticker_pack_authority", authority(context))
            putExtra("sticker_pack_name", name)
            setPackage(app.pkg)
        }

    /**
     * Sends one sticker to a WhatsApp chat (the user picks the chat). WhatsApp sends a 512 x 512 WebP
     * as a sticker; from the chat it can be long-pressed and added to favourites.
     */
    fun sendSticker(context: Context, file: java.io.File, app: WaApp?): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (app != null) setPackage(app.pkg)
        }
        return if (app != null) send else Intent.createChooser(send, null)
    }

    /** Tells WhatsApp (if it is listening) that a pack's contents changed. */
    fun notifyChanged(context: Context) {
        val base = Uri.parse("content://${authority(context)}")
        runCatching { context.contentResolver.notifyChange(base.buildUpon().appendPath(StickerContentProvider.METADATA).build(), null) }
    }
}
