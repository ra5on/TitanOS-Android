package io.github.ra5on.titanos.net

import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Geräteschlüssel nur für Tests: dasselbe Verfahren wie im Keystore (EC P-256, SHA256withECDSA). */
class SoftwareDeviceKey : DeviceKey {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val public: PublicKey get() = pair.public

    override fun publicKeyDer(): ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(data)
        sign()
    }
    override fun delete() = Unit

    fun verify(data: ByteArray, signature: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(pair.public)
        update(data)
        verify(signature)
    }
}
