package io.github.ra5on.titanos.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecretsTest {
    @get:Rule val dir = TemporaryFolder()

    /** Stellvertreter für den Keystore: kehrt die Bytes um und merkt, dass verschlüsselt wurde. */
    private class FlipBox : SecretBox {
        var sealed = 0
        var destroyed = false
        override fun seal(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray().also { sealed++ }
        override fun open(sealed: ByteArray): ByteArray = sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun destroy() {
            destroyed = true
        }
    }

    @Test
    fun keysOnlySealedOnDisk() {
        val box = FlipBox()
        val file = dir.root.resolve("vpn/keys.bin")
        val secrets = VpnSecrets(file, box)
        assertNull(secrets.load())
        secrets.save(VpnKeys("cHJpdmF0ZS1rZXktb2YtdGhlLXBob25lLTMyYnl0ZXM=", "cHJlc2hhcmVkLWtleS1vZi10aGUtcGhvbmUtMzJiPT0="))
        assertEquals(1, box.sealed)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("private key in clear on disk", raw.contains("cHJpdmF0ZS1rZXkt"))
        val back = secrets.load()!!
        assertEquals("cHJpdmF0ZS1rZXktb2YtdGhlLXBob25lLTMyYnl0ZXM=", back.privateKey)
        // Nie im Klartext in Protokollen oder Ausnahmen.
        assertFalse(back.toString().contains("cHJ"))
        secrets.clear()
        assertFalse(file.exists())
        assertTrue(box.destroyed)
    }

    @Test
    fun damagedFileIsNoKey() {
        val file = dir.root.resolve("keys.bin")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(VpnSecrets(file, FlipBox()).load())
    }
}
