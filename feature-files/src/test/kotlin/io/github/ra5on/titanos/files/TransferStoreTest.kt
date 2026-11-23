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

    @Test
    fun finishedEntriesStaySevenDays() {
        var now = 1_000_000_000_000L
        val s = TransferStore(null) { now }
        val old = s.add(upload("old"))
        s.update(old.id) { it.copy(state = TransferState.Done, finishedAtMs = now) }
        val running = s.add(upload("running"))
        now += 6 * DAY
        s.prune()
        assertTrue(s.get(old.id) != null)
        now += 2 * DAY
        s.prune()
        // Älter als 7 Tage: weg; Laufendes bleibt, egal wie alt.
        assertEquals(null, s.get(old.id))
        assertTrue(s.get(running.id) != null)
    }

    @Test
    fun oldEntriesAreGoneAfterARestart() {
        val file = File(tmp.root, "transfers.json")
        var now = 1_000_000_000_000L
        val a = TransferStore(file) { now }
        val t = a.add(upload("a"))
        a.update(t.id) { it.copy(state = TransferState.Failed, finishedAtMs = now) }
        now += 8 * DAY
        assertEquals(0, TransferStore(file) { now }.items.size)
    }

    @Test
    fun listenersSeeStateChangesOutsideTheLock() {
        val s = TransferStore(null)
        val seen = mutableListOf<Pair<TransferState?, TransferState>>()
        s.listen { before, after ->
            // Aus dem Hörer zurück in den Speicher lesen darf nicht hängen.
            s.summary()
            seen += before?.state to after.state
        }
        val t = s.add(upload("a"))
        s.update(t.id) { it.copy(state = TransferState.Running) }
        s.update(t.id) { it } // nichts geändert: keine Meldung
        s.update(t.id) { it.copy(state = TransferState.Done) }
        assertEquals(listOf(null to TransferState.Waiting, TransferState.Waiting to TransferState.Running, TransferState.Running to TransferState.Done), seen)
    }

    @Test
    fun summaryCountsActiveDownloadsAndUploadsButNotSharing() {
        val s = TransferStore(null)
        s.add(Transfer(0, "a.pdf", Direction.Download, "D", "r", "", done = 1_000, total = 4_000, state = TransferState.Running))
        s.add(upload("b.pdf").copy(done = 1_000, total = 4_000))
        s.add(Transfer(0, "c.jpg", Direction.Share, "D", "r", "", done = 0, total = 9_000, state = TransferState.Running, runner = TransferSource.App))
        s.add(Transfer(0, "", Direction.Upload, "Fotos/Handy", "", "", state = TransferState.Done, runner = TransferSource.Backup, photos = 3))
        val sum = s.summary()
        assertEquals(2, sum.count)
        assertEquals(1, sum.downloads)
        assertEquals(1, sum.uploads)
        assertEquals(0.25f, sum.fraction, 0.001f)
        // Der älteste Eintrag steht zuerst im Text („a.pdf und 1 weitere“).
        assertEquals("a.pdf", sum.first)
    }

    @Test
    fun latestForFindsTheRowOfAFileAndIgnoresBackups() {
        val s = TransferStore(null)
        val first = s.add(Transfer(0, "x.pdf", Direction.Download, "D", "r", "Privat", state = TransferState.Done, uri = "content://d/1"))
        val second = s.add(Transfer(0, "x.pdf", Direction.Download, "D", "r", "Privat"))
        assertEquals(second.id, s.latestFor("r", "Privat", "x.pdf")?.id)
        assertEquals(null, s.latestFor("r", "", "x.pdf"))
        assertTrue(s.get(first.id)!!.openable)
        assertTrue(!s.get(second.id)!!.openable)
    }

    @Test
    fun onlyQueuedTransfersCanBeRetried() {
        val failed = upload("a").copy(state = TransferState.Failed)
        assertTrue(failed.retryable)
        assertTrue(!failed.copy(runner = TransferSource.App).retryable)
        assertTrue(!failed.copy(runner = TransferSource.Backup).retryable)
        assertTrue(!failed.copy(state = TransferState.Done).retryable)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
