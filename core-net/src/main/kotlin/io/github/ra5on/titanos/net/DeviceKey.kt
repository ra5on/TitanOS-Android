package io.github.ra5on.titanos.net

import java.util.Base64

/**
 * Der Schlüssel dieses Geräts (ECDSA P-256). Am NAS liegt nur der öffentliche
 * Teil; der private bleibt im Android Keystore ([KeystoreDeviceKey]).
 */
interface DeviceKey {
    /** SubjectPublicKeyInfo, DER. */
    fun publicKeyDer(): ByteArray

    /** SHA256withECDSA, ASN.1/DER. */
    fun sign(data: ByteArray): ByteArray

    /** Löscht den Schlüssel (nach Abmelden oder `app_unknown`). */
    fun delete()
}

internal fun DeviceKey.publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKeyDer())

internal fun DeviceKey.signBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(sign(data))
