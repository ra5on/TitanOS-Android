package io.github.ra5on.titanos.agent.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun blocksSplitAcrossChunks() {
        val p = SseParser()
        val text = "event: content\ndata: {\"type\":\"content\",\"text\":\"Hal\"}\n\nevent: content\ndata: {\"text\":\"lo\"}\n\n"
        val out = text.chunked(7).flatMap { p.feed(it) }
        assertEquals(listOf("content", "content"), out.map { it.event })
        assertEquals(AgentEvent.Content("Hal"), out[0].toEvent())
        assertEquals(AgentEvent.Content("lo"), out[1].toEvent())
    }

    @Test
    fun commentsAndKeepAliveAreSkipped() {
        val p = SseParser()
        assertTrue(p.feed(": keep-alive\n\n").isEmpty())
        val out = p.feed(": x\nevent: end\ndata: {}\n\n")
        assertEquals(AgentEvent.End, out.single().toEvent())
    }

    @Test
    fun crlfAndBareCrEndLines() {
        val p = SseParser()
        val a = p.feed("event: state\r\ndata: {\"state\":\"waiting\"}\r")
        val b = p.feed("\n\r\n")
        assertTrue(a.isEmpty())
        assertEquals(AgentEvent.State("waiting", null), b.single().toEvent())
        val c = p.feed("event: reset\rdata: {}\r\r")
        assertEquals(AgentEvent.Reset, c.single().toEvent())
    }

    @Test
    fun multipleDataLinesAreJoined() {
        val p = SseParser()
        val out = p.feed("event: content\ndata: {\"text\":\ndata: \"a\"}\n\n")
        assertEquals("{\"text\":\n\"a\"}", out.single().data)
        assertEquals(AgentEvent.Content("a"), out.single().toEvent())
    }

    @Test
    fun blockWithoutNameOrBrokenJsonIsIgnored() {
        val p = SseParser()
        assertTrue(p.feed("data: {}\n\n").isEmpty())
        assertNull(SseBlock("content", "{kaputt").toEvent())
        assertNull(SseBlock("unknown", "{}").toEvent())
    }

    @Test
    fun snapshotApprovalStepAndEntry() {
        val snap = SseBlock(
            "snapshot",
            """{"type":"snapshot","entries":[{"id":"u1","at":"2026-11-20T10:00:00Z","role":"user","content":"Hi"}],"content":"Teil","state":"running","pending":null,"extra":1}""",
        ).toEvent() as AgentEvent.Snapshot
        assertEquals("Teil", snap.content)
        assertEquals("u1", snap.entries.single().id)
        assertEquals("running", snap.state)
        assertNull(snap.plan)
        val ap = SseBlock("approval", """{"type":"approval","pending":{"id":"p1","tool":"vm_action","args":{"vm":"HAOS","action":"update"},"level":"danger","summary":"vm.update (name=HAOS)","at":"2026-11-20T10:00:00Z"}}""").toEvent()
        assertEquals("p1", (ap as AgentEvent.Approval).pending.id)
        val step = SseBlock("step", """{"type":"step","step":{"id":"s1","role":"tool","tool":"storage_overview"}}""").toEvent()
        assertEquals("storage_overview", (step as AgentEvent.Step).step.tool)
        val entry = SseBlock("entry", """{"type":"entry","entry":{"id":"a1","role":"assistant","content":"Fertig"}}""").toEvent()
        assertEquals("Fertig", (entry as AgentEvent.Saved).entry.content)
        val plan = SseBlock("plan", """{"type":"plan","plan":[{"text":"A","done":true}]}""").toEvent()
        assertEquals(listOf(PlanStep("A", true)), (plan as AgentEvent.Plan).plan)
    }
}
