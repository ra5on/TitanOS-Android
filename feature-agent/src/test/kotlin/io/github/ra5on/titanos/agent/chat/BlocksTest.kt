package io.github.ra5on.titanos.agent.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlocksTest {
    @Test
    fun stepsBetweenQuestionAndAnswerAreFolded() {
        val entries = listOf(
            Entry(id = "u", role = "user", content = "Frage"),
            Entry(id = "a0", role = "assistant", thinking = "erst lesen", content = ""),
            Entry(id = "t1", role = "tool", tool = "calendar_list"),
            Entry(id = "n0", role = "note", note = "badcall"),
            Entry(id = "t2", role = "tool", tool = "storage_overview"),
            Entry(id = "n1", role = "note", note = "approved"),
            Entry(id = "a1", role = "assistant", content = "Antwort"),
            Entry(id = "n2", role = "note", note = "stopped"),
        )
        val b = blocks(entries)
        assertEquals(4, b.size)
        val steps = b[1] as Block.Steps
        assertEquals(listOf("t1", "t2", "n1"), steps.steps.map { it.id })
        assertEquals("erst lesen", steps.thinking)
        assertEquals("a1", (b[2] as Block.Answer).entry.id)
        assertEquals("n2", (b[3] as Block.Note).entry.id)
    }

    @Test
    fun choicesWithoutTextAreAnAnswer() {
        val b = blocks(listOf(Entry(id = "a", role = "assistant", content = " ", choices = listOf("Ja", "Nein"))))
        assertEquals("a", (b.single() as Block.Answer).entry.id)
    }

    @Test
    fun stepWordsKindsAndDetail() {
        assertEquals("storage", stepWord("storage_overview"))
        assertEquals("", stepWord("calendar_list"))
        assertEquals(StepKind.Web, stepKind("web_read"))
        assertEquals(StepKind.Write, stepKind("file_write"))
        assertEquals(StepKind.Files, stepKind("files_list"))
        assertEquals(StepKind.System, stepKind("docker_list"))
        assertEquals(StepKind.Switch, stepKind("ha_call"))
        assertEquals(StepKind.Home, stepKind("ha_state"))
        val args = buildJsonObject {
            put("root", JsonPrimitive("share/Daten"))
            put("path", JsonPrimitive("Rechnungen"))
            put("query", JsonPrimitive("Telekom"))
        }
        assertEquals("Daten › Rechnungen · Telekom", stepDetail(args))
        assertEquals(true, stepFailed(Entry(role = "tool", ok = false, result = "Error: nope")))
    }

    @Test
    fun approvalTitles() {
        val fromSummary = approvalTitle(Pending(tool = "vm_action", summary = "vm.update (name=HAOS)"))
        assertEquals(AskTitle("vm.update", "HAOS"), fromSummary)
        val fromArgs = approvalTitle(Pending(tool = "container_action", args = buildJsonObject {
            put("action", JsonPrimitive("restart"))
            put("container", JsonPrimitive("homeassistant"))
        }))
        assertEquals(AskTitle("container.restart", "homeassistant"), fromArgs)
        val path = approvalTitle(Pending(tool = "file_delete", args = buildJsonObject { put("path", JsonPrimitive("a/b/c.txt")) }))
        assertEquals(AskTitle("file.delete", "c.txt"), path)
        assertEquals(AskTitle("power.reboot", ""), approvalTitle(Pending(tool = "power_reboot")))
        assertNull(approvalTitle(Pending(tool = "mail_send", summary = "Mail an x")))
    }
}
