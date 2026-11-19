package io.github.ra5on.titanos.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Geräteschlüssel im Android Keystore: EC P-256, nur zum Unterschreiben, nicht exportierbar. */
class KeystoreDeviceKey(private val alias: String = DEFAULT_ALIAS) : DeviceKey {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun ensure(): KeyStore.PrivateKeyEntry {
        val ks = keyStore()
        (ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        gen.generateKeyPair()
        return ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    override fun publicKeyDer(): ByteArray = ensure().certificate.publicKey.encoded

    override fun sign(data: ByteArray): ByteArray {
        val key: PrivateKey = ensure().privateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    override fun delete() {
        keyStore().deleteEntry(alias)
    }

    companion object {
        const val DEFAULT_ALIAS = "titanos-device"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
