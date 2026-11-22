package io.github.ra5on.titanos.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mehrere NAS: Liste, Wahl und Übernahme einer bestehenden Einzelkopplung. */
class NasRegistryTest {
    private fun pairing(user: String = "zeus", spki: String = "pinA", server: String = "192.168.1.5:8443", host: String = "nas") =
        Pairing(user, "dev-$user-$spki", listOf(PinnedAddress(server, spki)), host)

    @Test
    fun anOldSinglePairingIsTakenOverWithItsOldNames() {
        // So hat die App vor mehreren NAS gespeichert (PrefsPairingStore, Einstellungen „pairing“).
        val legacy = MemoryRegistryStorage(mapOf("pairing" to TitanJson.encodeToString(Pairing.serializer(), pairing())))
        val storage = MemoryRegistryStorage()
        val registry = NasRegistry(storage, legacy)

        val only = registry.profiles.single()
        assertEquals(NasRegistry.LEGACY_ID, only.id)
        assertEquals(only, registry.active)
        assertEquals(pairing(), only.pairing)
        // Derselbe Schlüssel im Keystore (das NAS kennt nur ihn) und dieselben Ablagen.
        assertEquals(KeystoreDeviceKey.DEFAULT_ALIAS, only.keyAlias)
        assertEquals("", NasNames.suffix(only.id))
        // Die Übernahme steht fest und gilt auch beim nächsten Start; der alte Eintrag bleibt für ein Zurückspielen.
        assertNotNull(storage.read("registry"))
        assertNotNull(legacy.read("pairing"))
        assertEquals(registry.snapshot(), NasRegistry(storage, legacy).snapshot())
    }

    @Test
    fun withoutOldPairingTheListIsEmpty() {
        val registry = NasRegistry(MemoryRegistryStorage(), MemoryRegistryStorage())
        assertTrue(registry.profiles.isEmpty())
        assertNull(registry.active)
    }

    @Test
    fun addingChoosesTheNewNasAndRemovingFallsBack() {
        val legacy = MemoryRegistryStorage(mapOf("pairing" to TitanJson.encodeToString(Pairing.serializer(), pairing())))
        val registry = NasRegistry(MemoryRegistryStorage(), legacy)
        val id = NasRegistry.newId()
        registry.add(NasProfile(id, pairing(user = "anna", spki = "pinB", server = "10.0.0.9:8443", host = "eltern")))
        assertEquals(id, registry.active?.id)
        assertEquals("titanos-device-$id", registry.active?.keyAlias)
        assertEquals("-$id", NasNames.suffix(id))
        registry.setActive(NasRegistry.LEGACY_ID)
        assertEquals("nas", registry.active?.name)
        registry.remove(NasRegistry.LEGACY_ID)
        assertEquals(id, registry.active?.id)
        // Die alte Einzelkopplung kommt nach dem Entfernen nicht wieder.
        assertNull(legacy.read("pairing"))
        assertTrue(NasRegistry(MemoryRegistryStorage(), legacy).profiles.isEmpty())
    }

    @Test
    fun activeStoreWritesTheChosenPairing() {
        val registry = NasRegistry(MemoryRegistryStorage(), MemoryRegistryStorage())
        val store = ActivePairingStore(registry)
        store.save(pairing())
        assertEquals(NasRegistry.LEGACY_ID, registry.active?.id)
        val adding = AddingPairingStore(registry, "nabc")
        assertNull(adding.load())
        adding.save(pairing(user = "anna", spki = "pinB"))
        assertEquals("nabc", registry.active?.id)
        store.save(pairing(user = "anna", spki = "pinB", host = "neu"))
        assertEquals("neu", registry.profile("nabc")?.pairing?.hostname)
        assertEquals("nas", registry.profile(NasRegistry.LEGACY_ID)?.pairing?.hostname)
        store.clear()
        assertEquals(NasRegistry.LEGACY_ID, registry.active?.id)
    }

    @Test
    fun sameNasAndAccountIsRecognisedByPinOrAddress() {
        assertTrue(NasRegistry.sameNas(pairing(), pairing(server = "nas.tail.ts.net:8443")))
        assertTrue(!NasRegistry.sameNas(pairing(), pairing(user = "anna")))
        assertTrue(!NasRegistry.sameNas(pairing(), pairing(spki = "pinB")))
        assertTrue(NasRegistry.sameNas(pairing(spki = ""), pairing(spki = "")))
        val registry = NasRegistry(MemoryRegistryStorage(), MemoryRegistryStorage())
        registry.add(NasProfile("n1", pairing()))
        assertEquals("n1", registry.findSame(pairing(server = "x:1"))?.id)
        assertNull(registry.findSame(pairing(user = "anna")))
    }

    @Test
    fun pairingTheSameNasAgainReplacesTheOldEntryAndKeepsItsData() {
        val registry = NasRegistry(MemoryRegistryStorage(), MemoryRegistryStorage())
        registry.add(NasProfile(NasRegistry.LEGACY_ID, pairing(), label = "Zuhause"))
        registry.add(NasProfile("n2", pairing(user = "anna", spki = "pinB")))
        val adding = AddingPairingStore(registry, "n3")
        adding.save(pairing().copy(device = "neu"))
        assertEquals(NasRegistry.LEGACY_ID, adding.landed)
        assertEquals(KeystoreDeviceKey.DEFAULT_ALIAS, adding.replacedKey)
        assertEquals(2, registry.profiles.size)
        val p = registry.profile(NasRegistry.LEGACY_ID)!!
        assertEquals("neu", p.pairing.device)
        assertEquals("Zuhause", p.label)
        assertEquals("titanos-device-n3", p.keyAlias)
        assertEquals(NasRegistry.LEGACY_ID, registry.active?.id)
    }

    @Test
    fun aNasFromALinkIsNeitherChosenNorOfferedToTheAgent() {
        val registry = NasRegistry(MemoryRegistryStorage(), MemoryRegistryStorage())
        registry.add(NasProfile("n1", pairing()))
        val adding = AddingPairingStore(registry, "n2", activate = false)
        adding.save(pairing(user = "anna", spki = "pinB"))
        assertEquals("n1", registry.active?.id)
        assertEquals(false, registry.profile("n2")?.agent)
        assertEquals(true, registry.profile("n1")?.agent)
    }

    @Test
    fun vpnOwnerIsForgottenWithItsNas() {
        val registry = NasRegistry(MemoryRegistryStorage(), MemoryRegistryStorage())
        registry.add(NasProfile("n1", pairing()))
        registry.add(NasProfile("n2", pairing(spki = "pinB")))
        registry.setVpn("n1")
        assertEquals("n1", registry.snapshot().vpn)
        registry.remove("n1")
        assertEquals("", registry.snapshot().vpn)
    }
}
