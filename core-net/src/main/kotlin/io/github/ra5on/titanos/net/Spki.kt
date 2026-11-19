package io.github.ra5on.titanos.net

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * SPKI-Fingerabdruck wie am NAS (docs/ANDROID-APP.md, TLS): SHA-256 über den
 * öffentlichen Schlüssel des Zertifikats (SubjectPublicKeyInfo, DER),
 * base64url ohne Auffüllung.
 */
object Spki {
    fun sha256(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    fun of(cert: X509Certificate): String = encode(sha256(cert))

    fun encode(hash: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)

    /** Dekodiert einen Wert aus der Kopplung; null, wenn er kein SHA-256 ist. */
    fun decode(value: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(value.trimEnd('=')).takeIf { it.size == 32 }
    } catch (_: IllegalArgumentException) {
        null
    }

    fun matches(cert: X509Certificate, pin: String?): Boolean {
        val want = pin?.let(::decode) ?: return false
        return MessageDigest.isEqual(sha256(cert), want)
    }

    /**
     * Anzeige zum Vergleich mit dem Browser („Adresse von Hand“): die ersten
     * 16 Bytes als Hex in Großbuchstaben, Vierergruppen, zwei Zeilen zu je vier
     * Gruppen. Der Browser zeigt dieselben acht Gruppen.
     */
    fun displayLines(hash: ByteArray): List<String> {
        val hex = hash.take(16).joinToString("") { "%02X".format(it.toInt() and 0xff) }
        val groups = hex.chunked(4)
        return groups.chunked(4).map { it.joinToString(" ") }
    }
}
