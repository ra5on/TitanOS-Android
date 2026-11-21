package io.github.ra5on.titanos.storage

import io.github.ra5on.titanos.admin.testing.FakeNas
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID

/** Die Anfragen des Speichers: Methode, Pfad und Körper wie das Web. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageApiTest {
    private val nas = FakeNas().storageFixtures().start()
    private val api = StorageApi(nas.env().http)

    @After fun end() = nas.close()

    private fun last(): Pair<String, JsonObject?> {
        val s = nas.seen.last()
        return "${s.method} ${s.path}" to s.body.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun status() = runBlocking {
        val s = api.status()
        assertEquals(6, s.volumes.size)
        assertEquals("writeback", s.volumes.first { it.name == "Data" }.cache?.mode)
        assertTrue(sleepListed(s.diskSleep))
    }

    @Test fun createSendsEraseAndIdentitiesWithoutPassword() = runBlocking {
        api.create(CreateBody("Media", "raid5", "ext4", listOf("sdj", "sdl"), listOf("sdj", "sdl"), mapOf("sdj" to "serial:WD-J1"), checks = Checks(true, true, false, false)))
        val (call, body) = last()
        assertEquals("POST storage/volumes", call)
        assertEquals(
            json("""{"name":"Media","level":"raid5","filesystem":"ext4","disks":["sdj","sdl"],"erase":["sdj","sdl"],"identities":{"sdj":"serial:WD-J1"},"checks":{"parity":true,"smartShort":true,"smartLong":false,"snapshots":false}}"""),
            body,
        )
    }

    @Test fun createEncryptedSendsPasswordOnlyInBody() = runBlocking {
        val secret = UUID.randomUUID().toString()
        api.create(CreateBody("Box", "raid1", "btrfs", listOf("sdj"), listOf("sdj"), emptyMap(), encrypt = true, checks = Checks(true, true, true, true), password = secret))
        val (_, body) = last()
        assertEquals("true", body!!["encrypt"].toString())
        assertEquals("\"$secret\"", body["password"].toString())
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains(secret) == true })
    }

    @Test fun deleteSendsNameAsConfirmation() = runBlocking {
        api.delete("Old")
        val (call, body) = last()
        assertEquals("DELETE storage/volumes/Old", call)
        assertEquals(json("""{"confirm":"Old"}"""), body)
    }

    @Test fun unlockKeepsPasswordOutOfLogs() = runBlocking {
        val secret = UUID.randomUUID().toString()
        api.unlock("Vault", secret)
        val (call, body) = last()
        assertEquals("POST storage/volumes/Vault/unlock", call)
        assertEquals(json("""{"password":"$secret"}"""), body)
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains(secret) == true || it.throwable?.message?.contains(secret) == true })
    }

    @Test fun passwordsAndLock() = runBlocking {
        api.passwords("Fast", "remove", "a", null)
        assertEquals("POST storage/volumes/Fast/passwords" to json("""{"action":"remove","current":"a"}"""), last())
        api.lock("Fast")
        assertEquals("POST storage/volumes/Fast/lock" to json("{}"), last())
    }

    @Test fun cache() = runBlocking {
        api.enableCache("Data", "sdk", "writethrough", "ssd:K1")
        assertEquals("PUT storage/volumes/Data/cache" to json("""{"mode":"writethrough","disk":"sdk","erase":true,"identity":"ssd:K1"}"""), last())
        api.cacheMode("Data", "writeback")
        assertEquals("PUT storage/volumes/Data/cache" to json("""{"mode":"writeback"}"""), last())
        api.disableCache("Data")
        assertEquals("DELETE storage/volumes/Data/cache", last().first)
    }

    @Test fun profileSpeedQuotasCheck() = runBlocking {
        api.profile("Fast", "raid1c3")
        assertEquals("PUT storage/volumes/Fast/profile" to json("""{"profile":"raid1c3"}"""), last())
        api.syncSpeed("Safe", "fast")
        assertEquals("PUT storage/volumes/Safe/sync-speed" to json("""{"speed":"fast"}"""), last())
        api.quotas("Safe", true)
        assertEquals("PUT storage/volumes/Safe/quotas" to json("""{"enabled":true}"""), last())
        api.check("Data", true)
        assertEquals("POST storage/volumes/Data/check" to json("""{"action":"start"}"""), last())
    }

    @Test fun addAndRemoveDisk() = runBlocking {
        api.addDisk("Fast", AddDiskBody("sdj", true, "serial:WD-J1", "raid1c3", null))
        assertEquals("POST storage/volumes/Fast/disks" to json("""{"disk":"sdj","erase":true,"identity":"serial:WD-J1","profile":"raid1c3"}"""), last())
        api.removeDisk("Old", "sdi")
        assertEquals("DELETE storage/volumes/Old/disks/sdi", last().first)
    }

    @Test fun diskSleepBodyOnlyKnownTargets() = runBlocking {
        val status = api.status().diskSleep
        val body = sleepBody(status, 30, mapOf("Data" to 60, "Gone" to 5), mapOf("serial:WD-J1" to 0, "serial:GONE" to 10))
        api.saveSleep(body)
        assertEquals("PUT storage/disk-sleep" to json("""{"idleMinutes":30,"volumes":{"Data":60},"disks":{"serial:WD-J1":0}}"""), last())
        // Ohne Liste der Platten nur der Standard (die gespeicherten Ausnahmen bleiben beim NAS).
        assertEquals(DiskSleepBody(10, null, null), sleepBody(DiskSleepStatus(idleMinutes = 0, available = true), 10, mapOf("Data" to 5), emptyMap()))
    }

    @Test fun replaceAndIdentify() = runBlocking {
        api.replace("Data", "sdc", "sdj", "serial:WD-J1")
        assertEquals("POST storage/volumes/Data/replace" to json("""{"old":"sdc","new":"sdj","erase":true,"identity":"serial:WD-J1"}"""), last())
        api.identify("sdc")
        assertEquals("POST storage/disks/sdc/identify" to json("""{"seconds":30}"""), last())
    }

    @Test fun importRepeatsNameItself() = runBlocking {
        val c = api.importCandidates().first()
        api.importPreview(ImportPreviewBody(c.id, c.fingerprint))
        assertEquals("POST storage/import/preview" to json("""{"id":"md:fixture","fingerprint":"fingerprint"}"""), last())
        api.importVolume(c, "Archiv", null)
        assertEquals("POST storage/import" to json("""{"id":"md:fixture","fingerprint":"fingerprint","name":"Archiv","confirm":"Archiv"}"""), last())
    }

    @Test fun snapshotDeleteSendsNameAsConfirmation() = runBlocking {
        api.snapshotAction("Fast", "delete", "before")
        assertEquals("POST storage/volumes/Fast/snapshots" to json("""{"action":"delete","name":"before","confirm":"before"}"""), last())
        api.snapshotAction("Fast", "create", "nightly")
        assertEquals("POST storage/volumes/Fast/snapshots" to json("""{"action":"create","name":"nightly","confirm":""}"""), last())
    }

    @Test fun growthConfirmOnlyOnStart() = runBlocking {
        nas.on("POST", "storage/volumes/Data/growth/preview", """{"volume":"Data","diskSerial":"WD-J1","originalDisks":3,"targetDisks":4}""")
        api.growthPreview("Data", GrowthBody("sdj", "serial:WD-J1", "Safe", false, ""))
        assertEquals("POST storage/volumes/Data/growth/preview" to json("""{"disk":"sdj","identity":"serial:WD-J1","recoveryVolume":"Safe","erase":false,"confirm":""}"""), last())
        api.grow("Data", GrowthBody("sdj", "serial:WD-J1", "Safe", true, "Data"))
        assertEquals("POST storage/volumes/Data/growth" to json("""{"disk":"sdj","identity":"serial:WD-J1","recoveryVolume":"Safe","erase":true,"confirm":"Data"}"""), last())
        api.cancelGrowth("Data")
        assertEquals("POST storage/growth/cancel" to json("""{"confirm":"Data"}"""), last())
        api.recoverGrowth("Data")
        assertEquals("POST storage/growth/recover" to json("""{"confirm":"Data"}"""), last())
    }

    @Test fun hintsKeepOtherKeys() = runBlocking {
        val hints = api.hints()
        val value = hintsWithDismissed(hints, listOf("Fast"))
        api.saveHints(value)
        assertEquals("PUT preferences/hints" to json("""{"other":"x","checksDismissed":["Fast"]}"""), last())
    }

    @Test fun memory() = runBlocking {
        nas.on("PUT", "system/memory", """{"settings":{"cachePercent":30}}""")
        api.saveMemory(30)
        assertEquals("PUT system/memory" to json("""{"cachePercent":30}"""), last())
    }
}
