package tech.openera.stickerbridge.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tech.openera.stickerbridge.BuildConfig
import tech.openera.stickerbridge.data.AppRelease
import tech.openera.stickerbridge.data.StickerApi
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** Android needs "Install unknown apps" allowed for Sticker Bridge first. */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Self-update without re-downloading from a browser:
 * check the API for a newer build, download it from the same domain,
 * verify its SHA-256 and signing key, then hand it to Android's installer (one tap).
 */
class UpdateManager(
    private val context: Context,
    private val api: StickerApi,
    private val currentVersion: Int = BuildConfig.VERSION_CODE,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val apkFile get() = File(context.cacheDir, "updates/update.apk").also { it.parentFile?.mkdirs() }

    suspend fun check(): AppRelease? {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return null
        _state.value = UpdateState.Checking
        return try {
            val r = api.latestApp()
            if (r != null && r.versionCode > currentVersion) {
                _state.value = UpdateState.Available(r); r
            } else {
                _state.value = UpdateState.UpToDate; null
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't check for updates"); null
        }
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun downloadAndInstall(release: AppRelease) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(release); return
        }
        try {
            _state.value = UpdateState.Downloading(release, 0f)
            val file = apkFile
            file.delete()
            api.downloadTo(release.apk, file) { done, total ->
                val t = if (total > 0) total else release.size
                if (t > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / t).coerceIn(0f, 1f))
            }
            val problem = verify(file, release)
            if (problem != null) {
                file.delete()
                io.sentry.Sentry.captureMessage("Update rejected: $problem (${release.versionName})")
                _state.value = UpdateState.Failed(problem, release); return
            }
            _state.value = UpdateState.Installing(release)
            install(file)
        } catch (e: Exception) {
            if (e !is java.io.IOException) io.sentry.Sentry.captureException(e)
            _state.value = UpdateState.Failed("Download failed. Check your connection.", release)
        }
    }

    /** Returns null if the file is safe to install, else a reason. */
    fun verify(file: File, release: AppRelease): String? {
        if (!sha256(file).equals(release.sha256, ignoreCase = true)) return "The download was damaged. Try again."
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return "The download isn't a valid app."
        if (archive.packageName != context.packageName) return "This update is for a different app."
        val mine = signatures(pm, installedInfo(pm))
        val theirs = signatures(pm, archive)
        // If Android can't tell us the keys, the installer still refuses a mismatch.
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) return "This update isn't signed by Sticker Bridge."
        return null
    }

    private suspend fun install(file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(pi.intentSender)
        }
    }

    fun onInstallResult(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION &&
            status != PackageInstaller.STATUS_FAILURE_ABORTED
        ) {
            io.sentry.Sentry.captureMessage("Update install failed: status $status ${message ?: ""}")
        }
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val release = (state.value as? UpdateState.Installing)?.release
            _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            } else {
                UpdateState.Failed(message ?: "The update couldn't be installed.", release)
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(file.path, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageInfo(context.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun signatures(pm: PackageManager, info: PackageInfo?): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info?.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info?.signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
