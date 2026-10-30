package tech.openera.stickerbridge.data

import android.util.Base64
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Where donations go. Only shown when signed by the owner's key (see [Donations.verify]). */
@Serializable
data class DonationInfo(
    /** e.g. https://paypal.me/name */
    val paypal: String? = null,
    /** MTN Mobile Money number, e.g. +237... */
    val momo: String? = null,
)

object Donations {
    /**
     * Checks the owner's signature (ECDSA P-256 over the exact payload text) with the public key built
     * into the app. A changed payload, a missing signature or another key all fail, and nothing is shown.
     */
    fun verify(signed: SignedDonation, publicKeyBase64: String): DonationInfo? = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signed.payload.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(signed.signature, Base64.DEFAULT))
        }
        if (!ok) return null
        val info = ApiJson.decodeFromString(DonationInfo.serializer(), signed.payload)
        DonationInfo(
            paypal = info.paypal?.takeIf { it.startsWith("https://paypal.me/") || it.startsWith("https://www.paypal.me/") },
            momo = info.momo?.takeIf { Regex("^\\+?[0-9 ]{8,16}$").matches(it) },
        ).takeIf { it.paypal != null || it.momo != null }
    }.getOrNull()
}
