package tech.openera.stickerbridge.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import tech.openera.stickerbridge.BuildConfig
import java.security.MessageDigest

/**
 * Checks that this copy of the app is signed with the Sticker Bridge release key. A rebuilt or
 * repackaged copy (for example one with someone else's donation details) fails, and the app then
 * hides the donation section. Debug and test builds have no expected key and always pass.
 */
object Integrity {
    @Suppress("DEPRECATION")
    fun genuine(context: Context, expected: String = BuildConfig.SIGNER): Boolean {
        if (expected.isEmpty()) return true
        return runCatching {
            val pm = context.packageManager
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    ?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val md = MessageDigest.getInstance("SHA-256")
            sigs.orEmpty().any { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.equals(expected, true) }
        }.getOrDefault(false)
    }
}
