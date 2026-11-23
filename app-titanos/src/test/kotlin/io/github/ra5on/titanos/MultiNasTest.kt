package io.github.ra5on.titanos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.net.KeystoreDeviceKey
import io.github.ra5on.titanos.net.NasProfile
import io.github.ra5on.titanos.net.NasRegistry
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.notify.channelFor
import io.github.ra5on.titanos.notify.Category
import io.github.ra5on.titanos.photos.SettingsStore
import io.github.ra5on.titanos.widgets.WidgetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Mehrere NAS: eine bestehende Installation mit einer Kopplung übernimmt ihre
 * Daten ohne neue Kopplung; ein zweites NAS hat eigene Ablagen; Entfernen
 * trifft nur das eine NAS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w412dp-h915dp")
class MultiNasTest {
    private val app: TitanApp get() = ApplicationProvider.getApplicationContext()

    private val home = Pairing("zeus", "devHome", listOf(PinnedAddress("192.168.1.5:8443", "pinHome")), "nas")
    private val parents = Pairing("zeus", "devParents", listOf(PinnedAddress("10.8.0.2:8443", "pinParents")), "eltern")

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
        clean()
    }

    private fun clean() {
        for (name in listOf("nas", "pairing", "photos", "notify-nas", "widgets")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() {
        clean()
        File(app.filesDir, "photos-state.json").delete()
    }

    /** So lag eine Kopplung vor mehreren NAS: PrefsPairingStore (Einstellungen „pairing“), Fotos in „photos“, Stand in photos-state.json. */
    private fun oldInstall() {
        app.getSharedPreferences("pairing", Context.MODE_PRIVATE).edit()
            .putString("pairing", TitanJson.encodeToString(Pairing.serializer(), home)).commit()
        app.getSharedPreferences("photos", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("root", "share/Fotos").putString("dir", "Handy").commit()
        File(app.filesDir, "photos-state.json").writeText("""{"lastSaved":42}""")
    }

    @Test
    fun anExistingInstallKeepsItsPairingAndData() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))

        val p = graph.activeProfile!!
        assertEquals(NasRegistry.LEGACY_ID, p.id)
        assertEquals(home, graph.pairing)
        // Derselbe Schlüssel im Keystore: das NAS kennt nur dessen öffentlichen Teil, keine neue Kopplung nötig.
        assertEquals(KeystoreDeviceKey.DEFAULT_ALIAS, p.keyAlias)
        // Foto-Sicherung: dieselben Einstellungen und derselbe Stand.
        val s = SettingsStore(app, p.id).load()
        assertTrue(s.enabled)
        assertEquals("share/Fotos", s.root)
        assertEquals(42L, io.github.ra5on.titanos.photos.BackupStore.get(app, p.id).snapshot().lastSaved)
        // Mitteilungen: dieselbe Datei und dieselben Kanäle wie bisher.
        assertEquals("notify-nas", channelFor(Category.Nas, p.id))
        assertNotNull(graph.api)
        assertNotNull(graph.photos)
    }

    @Test
    fun aSecondNasHasItsOwnStoresAndSwitchingKeepsBoth() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        val id = NasRegistry.newId()
        graph.registry.add(NasProfile(id, parents))
        graph.switchTo(NasRegistry.LEGACY_ID)
        graph.switchTo(id)

        assertEquals(parents, graph.pairing)
        assertEquals("titanos-device-$id", graph.activeProfile!!.keyAlias)
        // Eigene Einstellungen: am zweiten NAS ist die Foto-Sicherung noch aus, am ersten weiter an.
        assertFalse(SettingsStore(app, id).load().enabled)
        assertTrue(SettingsStore(app, NasRegistry.LEGACY_ID).load().enabled)
        assertEquals("notify-nas-$id", channelFor(Category.Nas, id))
        assertEquals(id, graph.photos!!.nas)
        // Sitzungen getrennt: jede Kopplung hat ihre eigene.
        assertTrue(graph.link(id)!!.sessions !== graph.link(NasRegistry.LEGACY_ID)!!.sessions)
        assertEquals(2, graph.profiles.size)
    }

    @Test
    fun forgettingOneNasLeavesTheOtherAlone() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        val id = NasRegistry.newId()
        graph.registry.add(NasProfile(id, parents))
        graph.switchTo(id)
        SettingsStore(app, id).save(SettingsStore(app, id).load().copy(enabled = true, root = "share/Eltern", dir = "Handy"))
        WidgetStore.choose(app, 7, id)
        NotifyRuntime.setNasOn(app, id, false)

        graph.forget(id, byNas = true)

        assertEquals(NasRegistry.LEGACY_ID, graph.activeProfile!!.id)
        assertEquals(1, graph.profiles.size)
        assertEquals("eltern", graph.endedByNas)
        assertFalse(graph.unpairedByNas)
        // Das andere NAS behält alles.
        assertTrue(SettingsStore(app, NasRegistry.LEGACY_ID).load().enabled)
        // Vom entfernten NAS bleibt nichts.
        assertFalse(SettingsStore(app, id).load().enabled)
        assertNull(WidgetStore.chosen(app, 7))
        assertTrue(NotifyRuntime.nasOn(app, id))
    }

    @Test
    fun theSameNasPairedAgainKeepsItsEntry() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        val adding = graph.beginAdd()
        adding.save(home.copy(device = "devNew"))
        graph.added(adding)
        assertEquals(1, graph.profiles.size)
        assertEquals("devNew", graph.pairing!!.device)
        assertNull(graph.adding)
    }

    @Test
    fun clusterNodeRoutesThroughTheNas() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        graph.setNode("b2", "nas2")
        assertEquals("b2", graph.pairing!!.node)
        assertEquals("nas2", graph.pairing!!.nodeName)
        graph.setNode("", "")
        assertEquals("", graph.pairing!!.node)
    }

    /**
     * 1.01: Mit gewähltem Knoten bleibt die Foto-Sicherung beim gekoppelten NAS
     * (Verbindung ohne ClusterRoute), der Dateimanager geht über ClusterRoute
     * zum Knoten. Vorher gingen Ordner und Upload zum Knoten, der Abschluss nicht.
     */
    @Test
    fun photoBackupStaysOnThePairedNasWithANodeChosen() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        graph.setNode("b2", "nas2")
        val link = graph.link(NasRegistry.LEGACY_ID)!!
        fun routed(c: io.github.ra5on.titanos.net.NasConnection) = c.endpoints.any { e -> e.client.interceptors.any { it is io.github.ra5on.titanos.net.ClusterRoute } }
        assertTrue(routed(link.connection()!!))
        assertFalse(routed(link.backupConnection()!!))
        assertNotNull(link.backupApi())
        assertNotNull(link.photosApi())
        assertNotNull(graph.photos!!.gallery)
    }

    @Test
    fun twoNasWithTheSameNameGetTheirOwnContactsAccount() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        // Ab Werk heißt jedes NAS „titanos“: dasselbe Konto auf zwei NAS ergäbe denselben Kontonamen.
        val id = NasRegistry.newId()
        graph.registry.add(NasProfile(id, home.copy(device = "other", addresses = listOf(PinnedAddress("10.1.1.1:8443", "pinOther")))))
        val names = graph.registry.profiles.map(graph::pimName)
        assertEquals("zeus@nas", names[0])
        assertEquals("zeus@nas (${id.takeLast(4)})", names[1])
    }

    @Test
    fun aNasFromALinkStaysUnchosenUntilPickedByHand() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        val adding = graph.beginAdd(fromLink = true)
        adding.save(parents)
        graph.added(adding)
        assertEquals(NasRegistry.LEGACY_ID, graph.activeProfile!!.id)
        assertEquals("eltern", graph.addedQuietly)
        val id = adding.landed
        assertFalse(graph.registry.profile(id)!!.agent)
        // Wechsel aus Widget oder Mitteilung ist keine bewusste Wahl, der Umschalter schon.
        graph.switchTo(id, deliberate = false)
        assertFalse(graph.registry.profile(id)!!.agent)
        graph.switchTo(NasRegistry.LEGACY_ID)
        graph.switchTo(id)
        assertTrue(graph.registry.profile(id)!!.agent)
    }

    @Test
    fun noSessionOfAnotherNasForAnUnknownPairing() {
        oldInstall()
        val graph = AppGraph(app, NasRegistry.prefs(app))
        assertTrue(graph.sessionsOf(home) === graph.link(NasRegistry.LEGACY_ID)!!.sessions)
        val failed = runCatching { graph.sessionsOf(parents) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun clusterNodesOnlyWhenThisNasManagesThem() {
        val json = """{"nodes":[{"id":"a","name":"nas","self":true,"online":true},{"id":"b","name":"nas2","online":false}],"version":"1.19.0","manages":true}"""
        assertEquals(listOf("a", "b"), clusterNodesOf(json).map { it.id })
        assertTrue(clusterNodesOf(json.replace("\"manages\":true", "\"manages\":false")).isEmpty())
    }
}
