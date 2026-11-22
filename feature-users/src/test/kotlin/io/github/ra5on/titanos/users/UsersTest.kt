package io.github.ra5on.titanos.users

import io.github.ra5on.titanos.admin.testing.FakeNas
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regeln wie users-view.ts, account-agent-data.ts und die Anfragen der Kontoseite. */
class UsersTest {
    private val nas = FakeNas().start()
    private val api = UsersApi(nas.env().http)

    @After fun end() = nas.close()

    private fun res(name: String) = javaClass.getResource("/users/$name")!!.readText()
    private val accounts by lazy { Json { ignoreUnknownKeys = true }.decodeFromString<List<Account>>(res("users.json")) }
    private fun a(name: String) = accounts.first { it.name == name }

    @Test fun rules() {
        assertTrue(validAccountName("sam"))
        assertTrue(validAccountName("kinder-tablet_2"))
        assertFalse(validAccountName("Sam"))
        assertFalse(validAccountName("2sam"))
        assertFalse(validAccountName("a".repeat(33)))
        assertEquals(10, passwordLength("ääääääääää"))
        assertEquals(listOf("owner", "admin", "user"), offeredRoles("owner"))
        assertEquals(listOf("admin", "user"), offeredRoles("admin"))
        assertEquals(emptyList<String>(), offeredRoles("user"))
        assertEquals(listOf("kinderzimmer-tablet"), filterAccounts(accounts, "locked").map { it.name })
        assertEquals(3, filterAccounts(accounts, "active").size)
    }

    @Test fun locking() {
        // Nie sich selbst; Besitzer nur durch Besitzer; der letzte Besitzer bleibt.
        assertFalse(mayLock("alex", "owner", a("alex"), accounts))
        assertTrue(mayLock("alex", "owner", a("sam"), accounts))
        assertFalse(mayLock("maria", "admin", a("alex"), accounts))
        assertTrue(mayLock("maria", "admin", a("sam"), accounts))
        assertFalse(mayLock("sam", "user", a("maria"), accounts))
        val two = accounts + Account("chris", role = "owner")
        assertTrue(mayLock("alex", "owner", two.first { it.name == "chris" }, two))
        assertFalse(mayLock("chris", "owner", a("alex"), accounts + Account("chris", role = "owner", disabled = true)))
    }

    @Test fun agentData() {
        val lines = agentDataLines(Json { ignoreUnknownKeys = true }.decodeFromString(res("agent-sam.json")))!!
        assertEquals("users.agentData.chats" to 12, lines.deleted[0])
        assertEquals("users.agentData.projects" to 2, lines.deleted[1])
        assertTrue(lines.deleted.contains("users.agentData.part.folder" to null))
        assertTrue(lines.deleted.contains("users.agentData.contacts" to 81))
        assertEquals("users.agentData.handedOver" to 1, lines.kept)
        assertNull(lines.note)
        assertNull(agentDataLines(AgentData()))
        assertEquals("users.agentData.nothing", agentDataLines(AgentData(available = true))!!.note)
        val off = agentDataLines(AgentData(available = false, calendarUnknown = true))!!
        assertEquals("users.agentData.unavailable", off.note)
        assertTrue(off.deleted.contains("users.agentData.part.calendar" to null))
    }

    @Test fun requests() = runBlocking {
        nas.on("GET", "shares", res("shares.json"))
        api.create("neu", "x".repeat(12), "user")
        api.setRole("sam", "admin")
        api.resetTwoFactor("sam", "user")
        api.lock("sam", true)
        api.lock("sam", false)
        api.password("sam", "y".repeat(12), null)
        api.password("alex", "z".repeat(12), "alt-passwort-1")
        api.saveApps("sam", AppsBody(apps = mapOf("photos" to false)))
        api.saveApps("sam", AppsBody(newApps = true))
        api.saveMail("sam", true)
        api.saveCamera("sam", "c1", CameraBody(live = true, recordings = true, names = true))
        val raw = api.sharesRaw().first { it["name"]!!.jsonPrimitive.content == "Documents" }
        api.setShareAccess(raw, "maria", "rw")
        api.delete("sam")
        assertEquals(
            listOf(
                "POST users", "PUT users/sam", "PUT users/sam", "POST users/sam/lock", "POST users/sam/unlock",
                "POST users/sam/password", "POST users/alex/password", "PUT users/sam/apps", "PUT users/sam/apps",
                "PUT users/sam/mail", "PUT users/sam/cameras", "PUT shares/Documents", "DELETE users/sam",
            ),
            nas.seen.map { it.method + " " + it.path },
        )
        val reset = Json.parseToJsonElement(nas.seen[2].body).jsonObject
        assertEquals("true", reset["resetTwoFactor"].toString())
        assertEquals("user", reset["role"]!!.jsonPrimitive.content)
        // Fremdes Passwort ohne „current“, das eigene mit.
        assertFalse(Json.parseToJsonElement(nas.seen[5].body).jsonObject.containsKey("current"))
        assertTrue(Json.parseToJsonElement(nas.seen[6].body).jsonObject.containsKey("current"))
        val share = Json.parseToJsonElement(nas.seen[11].body).jsonObject
        assertFalse(share.containsKey("quotaStatus"))
        assertEquals("rw", share["access"]!!.jsonObject["maria"]!!.jsonPrimitive.content)
        assertEquals("ro", share["access"]!!.jsonObject["sam"]!!.jsonPrimitive.content)
        assertEquals("2000000000", share["quota"].toString())
        val cam = Json.parseToJsonElement(nas.seen[10].body).jsonObject["cameras"]!!.jsonObject["c1"]!!.jsonObject
        assertEquals("true", cam["names"].toString())
    }
}
