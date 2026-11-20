package io.github.ra5on.titanos.agent.chat

import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentModelTest {
    private val api = AgentApi(NasConnection(emptyList(), null))

    @Test
    fun withoutNetworkSendingFailsAtOnceAndKeepsTheText() {
        val m = AgentModel(api, ChatCache(null), CoroutineScope(Dispatchers.Unconfined), online = { false })
        m.draft = "Wie warm ist die Platte?"
        m.send()
        assertEquals(AgentProblem.Offline, m.error)
        assertEquals("Wie warm ist die Platte?", m.draft)
        assertFalse(m.sending)
    }

    @Test
    fun cachedListIsShownBeforeTheNasAnswers() {
        val dir = Files.createTempDirectory("agent").toFile()
        val file = File(dir, "lists.json")
        val chats = listOf(
            ChatInfo("c1", "Alt", updated = "2026-11-19T10:00:00Z"),
            ChatInfo("c2", "Neu", updated = "2026-11-20T10:00:00Z"),
            ChatInfo("c3", "Archiviert", archived = true, updated = "2026-11-20T11:00:00Z"),
        )
        ChatCache(file).save(CachedLists("2026-11-20T12:00:00Z", chats, listOf(Project("p1", "Haus")), AgentStatus(model = "qwen3:14b")))
        val m = AgentModel(api, ChatCache(file), CoroutineScope(Dispatchers.Unconfined))
        assertEquals(listOf("c2", "c1"), m.visibleChats().map { it.id })
        assertEquals(listOf("c1"), m.visibleChats("alt").map { it.id })
        assertEquals("qwen3:14b", m.shownModel)
        assertEquals("2026-11-20T12:00:00Z", m.cachedAt)
        ChatCache(file).clear()
        assertFalse(file.exists())
        dir.deleteRecursively()
    }

    @Test
    fun choosingTheDefaultModelForANewChatFollowsTheSettings() {
        val file = Files.createTempFile("agent", ".json").toFile()
        ChatCache(file).save(CachedLists(status = AgentStatus(model = "a", models = listOf(Model("a", levels = listOf("low")), Model("b")))))
        val m = AgentModel(api, ChatCache(file), CoroutineScope(Dispatchers.Unconfined))
        m.chooseThink("low")
        m.chooseModel("b")
        assertEquals("b", m.model)
        assertEquals("", m.think)
        m.chooseModel("a")
        assertEquals("", m.model)
        file.delete()
    }
}
