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

    /** Tells WhatsApp (if it is listening) that a pack's contents changed. */
    fun notifyChanged(context: Context) {
        val base = Uri.parse("content://${authority(context)}")
        runCatching { context.contentResolver.notifyChange(base.buildUpon().appendPath(StickerContentProvider.METADATA).build(), null) }
    }
}
