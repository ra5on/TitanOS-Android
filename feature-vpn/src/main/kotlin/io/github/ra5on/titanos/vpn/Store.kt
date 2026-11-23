package io.github.ra5on.titanos.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Einstellungen, Zugang (ohne Schlüssel) und der Eingriff von Hand, in den privaten Einstellungen der App. */
class VpnStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun settings(): VpnSettings = read(KEY_SETTINGS, VpnSettings.serializer()) ?: VpnSettings()

    fun saveSettings(s: VpnSettings) {
        prefs.edit().putString(KEY_SETTINGS, TitanJson.encodeToString(VpnSettings.serializer(), s)).apply()
    }

    fun profile(): VpnProfile? = read(KEY_PROFILE, VpnProfile.serializer())

    fun saveProfile(p: VpnProfile?) {
        prefs.edit().apply {
            if (p == null) remove(KEY_PROFILE) else putString(KEY_PROFILE, TitanJson.encodeToString(VpnProfile.serializer(), p))
        }.commit()
    }

    /** Eingriff von Hand im Modus Automatisch und das Netz, in dem er galt. */
    fun override(): Pair<Boolean, String>? {
        if (!prefs.contains(KEY_OVERRIDE)) return null
        return prefs.getBoolean(KEY_OVERRIDE, false) to prefs.getString(KEY_OVERRIDE_NET, "").orEmpty()
    }

    fun saveOverride(value: Boolean?, net: String) {
        prefs.edit().apply {
            if (value == null) remove(KEY_OVERRIDE).remove(KEY_OVERRIDE_NET) else putBoolean(KEY_OVERRIDE, value).putString(KEY_OVERRIDE_NET, net)
        }.apply()
    }

    /** Der Tunnel lief beim letzten Mal (nach einem Neustart des Prozesses im Modus „von Hand“ wieder aufbauen). */
    var wasUp: Boolean
        get() = prefs.getBoolean(KEY_WAS_UP, false)
        set(v) {
            prefs.edit().putBoolean(KEY_WAS_UP, v).apply()
        }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun <T> read(key: String, s: kotlinx.serialization.KSerializer<T>): T? = prefs.getString(key, null)?.let {
        try {
            TitanJson.decodeFromString(s, it)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
        const val KEY_PROFILE = "profile"
        const val KEY_OVERRIDE = "override"
        const val KEY_OVERRIDE_NET = "override_net"
        const val KEY_WAS_UP = "was_up"
    }
}

/** Ver- und Entschlüsseln; in der App mit einem Schlüssel aus dem Android Keystore. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    fun destroy() {}
}

/**
 * AES-256-GCM mit einem Schlüssel im Android Keystore, der das Gerät nie
 * verlässt. Der private WireGuard-Schlüssel selbst kann nicht in den Keystore
 * (wireguard-go braucht die rohen 32 Bytes); er liegt deshalb nur damit
 * verschlüsselt auf dem Handy.
 */
class KeystoreBox(private val alias: String = "titanos-vpn") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV))
        return c.doFinal(sealed, IV, sealed.size - IV)
    }

    override fun destroy() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val IV = 12
    }
}

/** Privater Schlüssel und Preshared Key des Zugangs; nie im Klartext gespeichert, nie protokolliert. */
class VpnKeys(val privateKey: String, val presharedKey: String) {
    // Kein Schlüssel in Protokollen, Ausnahmen oder Bildtests.
    override fun toString(): String = "VpnKeys(hidden)"
}

@Serializable
private data class SealedKeys(val privateKey: String, val presharedKey: String)

/** Die Schlüssel in `noBackupFilesDir`, nur mit [box] verschlüsselt. */
class VpnSecrets(private val file: File, private val box: SecretBox) {
    fun save(keys: VpnKeys) {
        val plain = TitanJson.encodeToString(SealedKeys.serializer(), SealedKeys(keys.privateKey, keys.presharedKey)).toByteArray()
        val tmp = File(file.parentFile, file.name + ".tmp")
        file.parentFile?.mkdirs()
        tmp.writeBytes(box.seal(plain))
        plain.fill(0)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("keys not stored")
        }
    }

    fun load(): VpnKeys? {
        if (!file.exists()) return null
        return try {
            val plain = box.open(file.readBytes())
            val k = TitanJson.decodeFromString(SealedKeys.serializer(), String(plain))
            plain.fill(0)
            VpnKeys(k.privateKey, k.presharedKey)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        file.delete()
        box.destroy()
    }

    companion object {
        fun of(context: Context): VpnSecrets = VpnSecrets(File(context.noBackupFilesDir, "vpn/keys.bin"), KeystoreBox())
    }
}
