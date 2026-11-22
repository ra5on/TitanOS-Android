package io.github.ra5on.titanos.net

import android.content.Context
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Eine Kopplung unter mehreren (mehrere NAS und Konten in einer App, PLAN §116 Paket F).
 *
 * [id] ist die feste Kennung in der App. Die Kopplung aus der Zeit vor mehreren
 * NAS heißt [NasRegistry.LEGACY_ID]; für sie gelten alle Namen von früher
 * (Schlüssel `titanos-device`, Einstellungen `photos`, Datei `photos-state.json`,
 * Aufträge `photos-scan` …). Dadurch übernimmt eine bestehende Installation
 * ihre Daten, ohne dass etwas umbenannt wird ([NasNames]).
 *
 * [label] ist ein eigener Name für die Anzeige (leer: Rechnername vom NAS).
 * [key] ist der Name des Schlüssels im Keystore, wenn er nicht zu [id] passt
 * (dasselbe NAS mit demselben Konto neu gekoppelt: die Daten bleiben bei [id],
 * der Schlüssel ist der neue).
 * [agent]: die Agent-App darf dieses NAS übernehmen. Ein NAS aus einem fremden
 * Link ist das erst, wenn man es in der App bewusst wählt.
 */
@Serializable
data class NasProfile(
    val id: String,
    val pairing: Pairing,
    val label: String = "",
    val key: String = "",
    val agent: Boolean = true,
) {
    /** Der Name in Listen, Kanälen und Widgets. */
    val name: String get() = label.ifBlank { pairing.hostname.ifBlank { pairing.addresses.firstOrNull()?.server.orEmpty() } }

    val keyAlias: String get() = key.ifEmpty { NasNames.keyAlias(id) }
}

@Serializable
data class NasRegistryState(
    val profiles: List<NasProfile> = emptyList(),
    val active: String = "",
    /** Das NAS, dem das eigene VPN gehört (Android erlaubt nur ein VPN gleichzeitig); leer: noch keins. */
    val vpn: String = "",
) {
    val activeProfile: NasProfile? get() = profiles.firstOrNull { it.id == active } ?: profiles.firstOrNull()

    fun profile(id: String?): NasProfile? = profiles.firstOrNull { it.id == id }
}

/** Namen je Kopplung; die alte Kopplung behält die Namen ohne Zusatz. */
object NasNames {
    /** Zusatz an Datei-, Einstellungs- und Auftragsnamen: leer für die alte Kopplung, sonst `-<id>`. */
    fun suffix(id: String): String = if (id == NasRegistry.LEGACY_ID || id.isEmpty()) "" else "-$id"

    fun keyAlias(id: String): String = KeystoreDeviceKey.DEFAULT_ALIAS + suffix(id)
}

/** Ablage der Liste; im Test ein Speicher, in der App SharedPreferences. */
interface RegistryStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class MemoryRegistryStorage(initial: Map<String, String> = emptyMap()) : RegistryStorage {
    val values = initial.toMutableMap()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * Alle Kopplungen und welche gerade gewählt ist. Beim ersten Lesen übernimmt
 * sie eine alte Einzelkopplung (`pairing` in den Einstellungen `pairing`)
 * als [LEGACY_ID] und lässt den alten Eintrag stehen, damit eine ältere
 * Fassung der App nach einem Zurückspielen weiter gekoppelt ist.
 */
class NasRegistry(private val storage: RegistryStorage, private val legacy: RegistryStorage? = null) {
    /** Hat dieser Start eine alte Einzelkopplung übernommen? (die App ignoriert dann den alten Kopplungslink der Aufgabe) */
    var migrated: Boolean = false
        private set

    @Volatile
    private var state: NasRegistryState = load()

    fun snapshot(): NasRegistryState = state

    val profiles: List<NasProfile> get() = state.profiles
    val active: NasProfile? get() = state.activeProfile

    fun profile(id: String?): NasProfile? = state.profile(id)

    @Synchronized
    fun setActive(id: String) {
        if (state.profile(id) == null || state.active == id) return
        save(state.copy(active = id))
    }

    /** Fügt eine neue Kopplung an und wählt sie ([activate]; die erste ist immer gewählt). */
    @Synchronized
    fun add(profile: NasProfile, activate: Boolean = true) {
        val rest = state.profiles.filterNot { it.id == profile.id }
        save(state.copy(profiles = rest + profile, active = if (activate || state.activeProfile == null) profile.id else state.active))
    }

    @Synchronized
    fun update(id: String, change: (NasProfile) -> NasProfile) {
        val now = state.profile(id) ?: return
        val next = change(now)
        if (next == now) return
        save(state.copy(profiles = state.profiles.map { if (it.id == id) next else it }))
    }

    /** Nimmt eine Kopplung weg; war sie gewählt, gilt die erste übrige. */
    @Synchronized
    fun remove(id: String) {
        val rest = state.profiles.filterNot { it.id == id }
        val active = if (state.active == id) rest.firstOrNull()?.id.orEmpty() else state.active
        save(state.copy(profiles = rest, active = active, vpn = if (state.vpn == id) "" else state.vpn))
        // Die alte Einzelkopplung nicht beim nächsten Start wieder übernehmen.
        if (id == LEGACY_ID) legacy?.write(LEGACY_KEY, null)
    }

    @Synchronized
    fun setVpn(id: String) {
        if (state.vpn == id) return
        save(state.copy(vpn = id))
    }

    /** Ist dieses NAS mit diesem Konto schon gekoppelt? (gleicher Pin oder gleiche Adresse und gleiches Konto) */
    fun findSame(pairing: Pairing): NasProfile? = state.profiles.firstOrNull { sameNas(it.pairing, pairing) }

    private fun save(next: NasRegistryState) {
        state = next
        storage.write(KEY, TitanJson.encodeToString(NasRegistryState.serializer(), next))
        // Die gewählte alte Kopplung auch unter dem alten Namen, damit ein Zurückspielen sie findet.
        next.profile(LEGACY_ID)?.let { legacy?.write(LEGACY_KEY, TitanJson.encodeToString(Pairing.serializer(), it.pairing)) }
    }

    private fun load(): NasRegistryState {
        storage.read(KEY)?.let { text ->
            runCatching { TitanJson.decodeFromString(NasRegistryState.serializer(), text) }.getOrNull()?.let { return it }
        }
        // Migration: eine Kopplung aus der Zeit vor mehreren NAS.
        val old = legacy?.read(LEGACY_KEY)?.let { runCatching { TitanJson.decodeFromString(Pairing.serializer(), it) }.getOrNull() }
            ?: return NasRegistryState()
        val taken = NasRegistryState(listOf(NasProfile(LEGACY_ID, old)), LEGACY_ID)
        storage.write(KEY, TitanJson.encodeToString(NasRegistryState.serializer(), taken))
        migrated = true
        return taken
    }

    companion object {
        /** Kennung der Kopplung, die es schon vor mehreren NAS gab. */
        const val LEGACY_ID = "main"

        /** Extra an Intents (Widget, Mitteilung, Kurzbefehl): die App wechselt zu diesem NAS. */
        const val EXTRA_NAS = "io.github.ra5on.titanos.NAS"
        private const val KEY = "registry"
        private const val LEGACY_KEY = "pairing"

        /** Kurze neue Kennung: Buchstaben und Ziffern, taugt für Datei-, Auftrags- und Kanalnamen. */
        fun newId(): String = "n" + UUID.randomUUID().toString().replace("-", "").take(10)

        fun sameNas(a: Pairing, b: Pairing): Boolean {
            if (a.user != b.user) return false
            val pinsA = a.addresses.filter { !it.isVpn }.map { it.spki }.filter { it.isNotEmpty() }.toSet()
            val pinsB = b.addresses.filter { !it.isVpn }.map { it.spki }.filter { it.isNotEmpty() }.toSet()
            if (pinsA.isNotEmpty() && pinsB.isNotEmpty()) return pinsA.intersect(pinsB).isNotEmpty()
            val serversA = a.addresses.filter { !it.isVpn }.map { it.server }.toSet()
            return b.addresses.any { !it.isVpn && it.server in serversA }
        }

        /** Die Liste der App in ihren privaten Einstellungen (`nas`), mit Übernahme aus `pairing`. */
        fun prefs(context: Context): NasRegistry {
            val app = context.applicationContext
            return NasRegistry(PrefsStorage(app, "nas"), PrefsStorage(app, "pairing"))
        }
    }
}

/** SharedPreferences als [RegistryStorage]; geschrieben wird mit commit (die Kopplung kommt vom NAS nur einmal). */
class PrefsStorage(context: Context, name: String) : RegistryStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
}

/**
 * Die gewählte Kopplung als [PairingStore] für Code, der nur eine kennt
 * (Kopplungsablauf, Geschwister-App): Laden gibt die gewählte, Speichern
 * ändert sie, Löschen nimmt sie weg.
 */
class ActivePairingStore(private val registry: NasRegistry) : PairingStore {
    override fun load(): Pairing? = registry.active?.pairing
    override fun save(pairing: Pairing) {
        val now = registry.active
        if (now == null) registry.add(NasProfile(NasRegistry.LEGACY_ID, pairing)) else registry.update(now.id) { it.copy(pairing = pairing) }
    }
    override fun clear() {
        registry.active?.let { registry.remove(it.id) }
    }
}

/**
 * Speichern legt eine neue Kopplung mit [id] an („NAS hinzufügen“); vorher
 * gibt es hier nichts zu laden. Mit dem Schlüssel [NasNames.keyAlias] von [id].
 */
class AddingPairingStore(
    private val registry: NasRegistry,
    val id: String,
    /** Das neue NAS gleich wählen (und der Agent-App anbieten)? Nicht bei einem Link aus dem Browser. */
    val activate: Boolean = true,
) : PairingStore {
    /**
     * Wo die Kopplung gelandet ist: [id], oder die Kennung einer schon
     * vorhandenen Kopplung mit demselben NAS und Konto. Dann bekommt diese die
     * neue Kopplung samt neuem Schlüssel und behält ihre Daten (keine doppelten Einträge).
     */
    @Volatile
    var landed: String = id
        private set

    /** Der Schlüssel der vorhandenen Kopplung vor dem Ersetzen (zum Löschen), sonst null. */
    @Volatile
    var replacedKey: String? = null
        private set

    override fun load(): Pairing? = registry.profile(landed)?.pairing
    override fun save(pairing: Pairing) {
        val same = registry.findSame(pairing)?.takeIf { it.id != id }
        if (same != null) {
            replacedKey = same.keyAlias.takeIf { it != NasNames.keyAlias(id) }
            landed = same.id
            registry.update(same.id) { it.copy(pairing = pairing.copy(node = ""), key = NasNames.keyAlias(id)) }
            if (activate) registry.setActive(same.id)
            return
        }
        val now = registry.profile(id)
        if (now == null) registry.add(NasProfile(id, pairing, agent = activate), activate) else registry.update(id) { it.copy(pairing = pairing) }
    }
    override fun clear() = registry.remove(landed)
}
