package io.github.ra5on.titanos.agent.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunFoldTest {
    private val user = Entry(id = "u1", role = "user", content = "Wie warm ist die Platte?")
    private fun fold(live: Live = Live()) = RunFold(Chat(id = "c1", state = ChatState.RUNNING, entries = listOf(user)), live)

    @Test
    fun contentStreamsAndAssistantEntryClearsIt() {
        val f = fold()
        f.apply(AgentEvent.Thinking("hm"))
        f.apply(AgentEvent.Content("Die Platte "))
        f.apply(AgentEvent.Content("hat 52 °C."))
        assertEquals("Die Platte hat 52 °C.", f.live.content)
        assertEquals("hm", f.live.thinking)
        f.apply(AgentEvent.Saved(Entry(id = "a1", role = "assistant", content = "Die Platte hat 52 °C.")))
        assertEquals("", f.live.content)
        assertEquals(listOf("u1", "a1"), f.chat.entries.map { it.id })
    }

    @Test
    fun toolEntryClearsTheRunningStepAndDuplicatesAreIgnored() {
        val f = fold()
        val step = Entry(id = "s1", role = "tool", tool = "storage_overview")
        f.apply(AgentEvent.Step(step))
        assertEquals(step, f.live.step)
        f.apply(AgentEvent.Saved(step.copy(ok = true, durationMs = 900)))
        f.apply(AgentEvent.Saved(step.copy(ok = true, durationMs = 900)))
        assertNull(f.live.step)
        assertEquals(2, f.chat.entries.size)
    }

    @Test
    fun firstNonSnapshotEventClearsOldLiveText() {
        val f = fold(Live(content = "alt"))
        f.apply(AgentEvent.Content("neu"))
        assertEquals("neu", f.live.content)
    }

    @Test
    fun snapshotReplacesLiveAndMergesEntriesById() {
        val f = fold(Live(content = "alt"))
        val changed = Entry(id = "u1", role = "user", content = "geändert")
        val tool = Entry(id = "t1", role = "tool", tool = "file_read")
        f.apply(AgentEvent.Snapshot(listOf(changed, tool), "denke", "Teil", null, ChatState.RUNNING, null, null, listOf(PlanStep("A"))))
        assertEquals(Live("denke", "Teil", null), f.live)
        assertEquals(listOf("u1", "t1"), f.chat.entries.map { it.id })
        assertEquals("geändert", f.chat.entries[0].content)
        assertEquals(listOf(PlanStep("A")), f.chat.plan)
    }

    @Test
    fun resetKeepsStepButDropsText() {
        val f = fold()
        val step = Entry(id = "s1", role = "tool", tool = "web_search")
        f.apply(AgentEvent.Step(step))
        f.apply(AgentEvent.Content("falsch"))
        f.apply(AgentEvent.Reset)
        assertEquals(Live(step = step), f.live)
    }

    @Test
    fun approvalMakesTheChatWait() {
        val f = fold()
        val p = Pending(id = "p1", tool = "vm_action", summary = "vm.update (name=HAOS)")
        f.apply(AgentEvent.Approval(p))
        assertEquals(ChatState.WAITING, f.chat.state)
        assertEquals(p, f.chat.pending)
        f.apply(AgentEvent.State(ChatState.IDLE, null))
        assertEquals(ChatState.IDLE, f.chat.state)
    }

    @Test
    fun afterEndFollowsAgainWhileRunningWithGrowingPause() {
        assertEquals(AfterEnd.FollowAgain(500), afterEnd(ChatState.RUNNING, 0))
        assertEquals(AfterEnd.FollowAgain(1000), afterEnd(ChatState.RUNNING, 1))
        assertEquals(AfterEnd.FollowAgain(8000), afterEnd(ChatState.RUNNING, 6))
        assertEquals(AfterEnd.Done, afterEnd(ChatState.RUNNING, MAX_FOLLOW_RETRIES))
        assertEquals(AfterEnd.Done, afterEnd(ChatState.IDLE, 0))
        assertEquals(AfterEnd.Done, afterEnd(ChatState.WAITING, 0))
        // Nicht lesbar (NAS antwortet nicht): 1 s, 2 s … höchstens 10 s.
        assertEquals(AfterEnd.FollowAgain(1000), afterEnd(null, 0))
        assertEquals(AfterEnd.FollowAgain(10_000), afterEnd(null, 5))
    }

    @Test
    fun mergeEntriesWithoutRunEntriesKeepsTheChat() {
        val list = listOf(user)
        assertEquals(list, mergeEntries(list, emptyList()))
    }
}
