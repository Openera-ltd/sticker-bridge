package tech.openera.stickerbridge.data

import kotlinx.serialization.Serializable

/**
 * Everything the app asks the server. One base address, versioned under /v1.
 * See docs/api.md for the server side of this contract.
 */
interface StickerApi {
    /** Ask for a one-time code to send to @stickerbridge on TikTok. */
    suspend fun startLink(installId: String): LinkStart

    /** Has the code arrived from a TikTok account yet? */
    suspend fun linkStatus(code: String, pollToken: String): LinkStatus

    /** Stickers received after [cursor] (null = from the beginning). */
    suspend fun stickers(token: String, cursor: String?): StickerPage

    /** Raw bytes of one sticker file. [path] comes from [RemoteSticker.file]. */
    suspend fun download(token: String?, path: String): ByteArray

    /** Streams a large file (an app update) to [dest], reporting bytes done and total. */
    suspend fun downloadTo(path: String, dest: java.io.File, onProgress: (Long, Long) -> Unit)

    suspend fun unlink(token: String)

    /** Deletes the account's stickers and link on the server. */
    suspend fun deleteData(token: String)

    suspend fun config(): RemoteConfig

    suspend fun latestApp(): AppRelease?
}

@Serializable
data class LinkStart(
    val code: String,
    val pollToken: String,
    val expiresAt: Long,
)

@Serializable
data class LinkStatus(
    /** "pending", "linked" or "expired". */
    val status: String,
    val token: String? = null,
    val account: Account? = null,
)

@Serializable
data class Account(
    val id: String,
    val username: String,
)

@Serializable
data class StickerPage(
    val items: List<RemoteSticker>,
    val cursor: String? = null,
)

@Serializable
data class RemoteSticker(
    val id: String,
    /** Path under the API base, e.g. "v1/stickers/abc/file". */
    val file: String,
    val animated: Boolean = false,
    val createdAt: Long = 0,
    val emojis: List<String> = emptyList(),
)

@Serializable
data class RemoteConfig(
    /** Versions below this must update before they can sync. */
    val minVersionCode: Int = 0,
    /** Short notice shown at the top of the app, e.g. during maintenance. */
    val notice: String? = null,
    val botUsername: String? = null,
)

@Serializable
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    /** Path under the API base, e.g. "v1/app/apk/2". */
    val apk: String,
    val sha256: String,
    val size: Long = 0,
    val notes: String = "",
    val mandatory: Boolean = false,
)

class ApiException(val code: Int, message: String) : Exception(message)
