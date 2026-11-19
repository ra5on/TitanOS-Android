package io.github.ra5on.titanos.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun upload(name: String) = Transfer(0, name, Direction.Upload, "Documents", "r1", "", total = 10, source = "file:///x/$name")

    @Test
    fun survivesARestartAndRunningWaitsAgain() {
        val file = File(tmp.root, "transfers.json")
        val a = TransferStore(file)
        val t = a.add(upload("a.pdf"))
        a.update(t.id) { it.copy(state = TransferState.Running, uploadId = "u1", done = 5) }
        val b = TransferStore(file)
        val again = b.get(t.id)!!
        assertEquals(TransferState.Waiting, again.state)
        assertEquals("u1", again.uploadId)
        // Neue Ids folgen auf die alten.
        assertTrue(b.add(upload("b.pdf")).id > t.id)
    }

    @Test
    fun keepsOnlyTheNewestFinished() {
        val s = TransferStore(null)
        repeat(TransferStore.KEEP_DONE + 10) { i ->
            val t = s.add(upload("f$i"))
            s.update(t.id) { it.copy(state = TransferState.Done) }
        }
        val active = s.add(upload("running"))
        assertTrue(s.items.count { !it.active } <= TransferStore.KEEP_DONE)
        assertTrue(s.get(active.id) != null)
        s.clearFinished()
        assertEquals(listOf(active.id), s.items.map { it.id })
    }

    @Test
    fun damagedFileStartsEmpty() {
        val file = File(tmp.root, "transfers.json").apply { writeText("{not json") }
        assertEquals(0, TransferStore(file).items.size)
    }
}
