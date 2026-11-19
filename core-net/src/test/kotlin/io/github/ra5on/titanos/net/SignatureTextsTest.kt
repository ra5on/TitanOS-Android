package io.github.ra5on.titanos.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class SignatureTextsTest {
    @Test
    fun pairTextIsPrefixNewlineCode() {
        assertArrayEquals("titanos-pair\nK7Q4-9XMT".toByteArray(), SignatureTexts.pair("K7Q4-9XMT"))
        assertEquals("titanos-pair\nabc_DEF-123", String(SignatureTexts.pair("abc_DEF-123")))
    }

    @Test
    fun sessionTextHasUserDeviceNonceInThisOrder() {
        assertEquals(
            "titanos-app-session\nzeus\nAbCdEfGhIjKlMnOp\nnonce-1",
            String(SignatureTexts.session("zeus", "AbCdEfGhIjKlMnOp", "nonce-1"), Charsets.UTF_8),
        )
    }

    @Test
    fun textsAreUtf8() {
        assertArrayEquals("titanos-app-session\njürgen\nd\nn".toByteArray(Charsets.UTF_8), SignatureTexts.session("jürgen", "d", "n"))
    }

    /** Wie das NAS prüft: öffentlicher Schlüssel als SPKI-DER base64, Signatur ASN.1/DER base64, SHA-256. */
    @Test
    fun signatureVerifiesLikeTheNas() {
        val key = SoftwareDeviceKey()
        val text = SignatureTexts.session("zeus", "dev", "n0nce")
        val sigB64 = key.signBase64(text)
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKeyBase64())))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(text)
            verify(Base64.getDecoder().decode(sigB64))
        }
        assertTrue(ok)
        // DER-Sequenz, nicht rohes r||s.
        assertEquals(0x30, Base64.getDecoder().decode(sigB64)[0].toInt())
    }
}
