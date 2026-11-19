package io.github.ra5on.titanos.net

import kotlinx.serialization.Serializable

/**
 * Eine gekoppelte Adresse: der beim Koppeln festgehaltene SPKI-Wert und ob die
 * Adresse ein öffentliches Zertifikat hat (dann gilt eine normale Prüfung als
 * Ersatz, wenn sich der Schlüssel bei der Erneuerung ändert).
 */
@Serializable
data class PinnedAddress(val server: String, val spki: String = "", val publicCert: Boolean = false) {
    val address: ServerAddress? get() = ServerAddress.parse(server)
}

/** Was nach der Kopplung bleibt. user und device kommen zusammen aus `pair/wait`. */
@Serializable
data class Pairing(
    val user: String,
    val device: String,
    val addresses: List<PinnedAddress>,
    val hostname: String = "",
)

interface PairingStore {
    fun load(): Pairing?
    fun save(pairing: Pairing)
    fun clear()
}

class MemoryPairingStore(private var value: Pairing? = null) : PairingStore {
    override fun load(): Pairing? = value
    override fun save(pairing: Pairing) {
        value = pairing
    }
    override fun clear() {
        value = null
    }
}
