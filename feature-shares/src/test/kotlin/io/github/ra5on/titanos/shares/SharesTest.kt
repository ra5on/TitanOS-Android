package io.github.ra5on.titanos.shares

import io.github.ra5on.titanos.admin.testing.FakeNas
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Regeln wie im Web (ShareDialog, share-view, share-delete) und die Anfragen ans NAS. */
class SharesTest {
    private val nas = FakeNas().start()
    private val api = SharesApi(nas.env().http)

    @After fun end() = nas.close()

    private fun res(name: String) = javaClass.getResource("/shares/$name")!!.readText()

    @Test fun names() {
        assertTrue(validShareName("Documents"))
        assertTrue(validShareName("Projekt 2026_a.b-c"))
        assertTrue(validShareName("Fotos Äpfel"))
        assertFalse(validShareName(" vorne"))
        assertFalse(validShareName("hinten."))
        assertFalse(validShareName("zwei  Leer"))
        assertFalse(validShareName("homes"))
        assertFalse(validShareName("IPC"))
        assertFalse(validShareName("a".repeat(33)))
        assertFalse(validShareName("a/b"))
    }

    @Test fun clients() {
        assertTrue(validClient("192.168.1.0/24"))
        assertTrue(validClient("10.0.0.5"))
        assertFalse(validClient("300.1.1.1"))
        assertFalse(validClient("10.0.0.0/33"))
        assertTrue(validClient("fd00::/64"))
        assertTrue(validClient("2001:db8:0:0:0:0:0:1"))
        assertFalse(validClient("2001:db8::1::2"))
        assertTrue(validClient("*.local"))
        assertTrue(validClient("mediaplayer.local"))
        assertFalse(validClient("host/24"))
        assertFalse(validClient("1.2.3"))
        assertEquals(listOf("a", "b"), clientLines(" a \n\n b "))
    }

    @Test fun listView() {
        val shares = Json { ignoreUnknownKeys = true }.decodeFromString<List<Share>>(res("shares.json"))
        val names = { v: ShareView, q: String -> visibleShares(shares, v, q, Locale.GERMAN).map { it.name } }
        assertEquals(listOf("Backups Mac", "Documents", "KI-Agent", "Media", "Projekt 2026 Planung"), names(ShareView(), ""))
        assertEquals(listOf("Media"), names(ShareView(protocol = "nfs"), ""))
        assertEquals(listOf("Projekt 2026 Planung"), names(ShareView(volume = "Safe"), ""))
        assertEquals(listOf("Media"), names(ShareView(), "fast med"))
        assertEquals("Projekt 2026 Planung", names(ShareView(descending = true), "").first())
        assertEquals("Media", names(ShareView(sort = "volume"), "")[3])
        assertEquals(ShareView(protocol = "all", sort = "name"), ShareView(protocol = "x", sort = "y").clean())
        assertTrue(nobodyCanUse(shares.first { it.name == "Projekt 2026 Planung" }))
        assertFalse(nobodyCanUse(shares.first { it.name == "Media" }))
    }

    @Test fun addresses() {
        val share = Share("Mein Ordner", smb = true, nfs = Nfs(enabled = true))
        val list = shareAddresses(share, "192.168.1.20")
        assertEquals("\\\\192.168.1.20\\Mein Ordner", list[0].second)
        assertEquals("smb://192.168.1.20/Mein%20Ordner", list[1].second)
        assertEquals("//192.168.1.20/Mein Ordner", list[3].second)
        assertEquals("192.168.1.20:/Mein Ordner", list[4].second)
    }

    @Test fun deletion() {
        assertEquals("share.use.docker", usageKey("docker"))
        assertEquals("share.use.other", usageKey("neu"))
        assertFalse(canDeleteData(null))
        assertFalse(canDeleteData(DataPreview(blockers = listOf(ShareUsage("vm")))))
        assertTrue(canDeleteData(DataPreview()))
        assertNull(deletionPercent(ShareDeletion(phase = "checking", totalFiles = 10)))
        assertEquals(40.0, deletionPercent(ShareDeletion(phase = "deleting", files = 4, totalFiles = 10))!!, 0.001)
    }

    @Test fun draftBody() {
        val d = ShareDraft.of(null, listOf("Data", "Fast"), "alex")
        assertEquals("Data", d.volume)
        assertEquals(mapOf("alex" to "rw"), d.access)
        val body = d.copy(name = "Neu", smb = false, guest = true, recycle = true, quotaGB = "1,5", nfs = "ro", clients = "10.0.0.0/8").body()
        // Ohne SMB keine SMB-Optionen, wie im Web.
        assertFalse(body.guest)
        assertFalse(body.recycle.enabled)
        assertEquals(1_500_000_000L, body.quota)
        assertTrue(body.nfs.enabled)
        assertEquals("ro", body.nfs.mode)
        assertTrue(ShareDraft(quotaGB = "0.01").quotaTooSmall)
        assertFalse(ShareDraft(quotaGB = "0.02").quotaTooSmall)
        val edit = ShareDraft.of(Share("X", quota = 1_234_000_000, nfs = Nfs(enabled = true, mode = "")), emptyList(), "alex")
        assertEquals("1.23", edit.quotaGB)
        assertEquals("rw", edit.nfs)
        assertEquals("access", errorTab("share_guard_locked", ShareDraft(smb = true)))
        assertNull(errorTab("share_guard_locked", ShareDraft(smb = false)))
        assertEquals("general", errorTab("invalid_option", ShareDraft(smb = false, quotaGB = "2")))
    }

    @Test fun requests() = runBlocking {
        nas.on("GET", "shares/Documents/delete-data", res("preview.json"))
        api.create(ShareDraft.of(null, listOf("Data"), "alex").copy(name = "Neu").body())
        api.update("Mein Ordner", ShareDraft(name = "Mein Ordner", volume = "Data").body())
        api.delete("Mein Ordner")
        val preview = api.preview("Documents")
        // Den Pfad der Vorschau schickt die App selbst als Bestätigung.
        api.purge("Documents", preview.path)
        api.unlock("Backups Mac")
        api.saveView(ShareView(volume = "Data", protocol = "smb", sort = "volume", descending = true, compact = false))
        val seen = nas.seen.map { it.method + " " + it.path }
        assertEquals(
            listOf(
                "POST shares",
                "PUT shares/Mein%20Ordner",
                "DELETE shares/Mein%20Ordner",
                "POST shares/Documents/delete-data",
                "POST shares/Backups%20Mac/unlock",
                "PUT preferences/shares",
            ),
            seen,
        )
        val created = Json.parseToJsonElement(nas.seen[0].body).jsonObject
        assertEquals("Neu", created["name"]!!.jsonPrimitive.content)
        assertEquals("rw", created["access"]!!.jsonObject["alex"]!!.jsonPrimitive.content)
        assertEquals(0L, created["quota"]!!.jsonPrimitive.long)
        assertTrue(created["smb"]!!.jsonPrimitive.boolean)
        val purge = Json.parseToJsonElement(nas.seen[3].body).jsonObject
        assertEquals("/mnt/Data/Documents", purge["path"]!!.jsonPrimitive.content)
        val view = Json.parseToJsonElement(nas.seen[5].body).jsonObject
        assertEquals("volume", view["sort"]!!.jsonPrimitive.content)
        assertFalse(view["compact"]!!.jsonPrimitive.boolean)
    }

    @Test fun recordedAnswers() = runBlocking {
        assertEquals(3, api.shares().size)
        assertEquals(listOf("alex", "sam"), api.accounts().map { it.name })
        assertEquals("active", api.storage().volumes.first { it.name == "Data" }.shareQuotas)
    }
}
