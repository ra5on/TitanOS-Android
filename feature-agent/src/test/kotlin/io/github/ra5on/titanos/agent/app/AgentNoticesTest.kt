package io.github.ra5on.titanos.agent.app

import io.github.ra5on.titanos.agent.app.AgentNotices.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Eine Antwort, zwei Melder (1.03): die App selbst und das NAS. Wer zuerst
 * kommt, klingelt; die zweite Meldung desselben Laufs bleibt still. Eine
 * weitere Antwort klingelt wieder.
 */
class AgentNoticesTest {
    private val answer = "Gern. Das NAS läuft normal: Volume tank ist gesund, 3 Container laufen, die VM Web-Server ist an."

    @Before
    fun clean() = AgentNotices.reset()

    @Test
    fun theSecondReporterOfOneRunStaysQuietInEitherOrder() {
        assertTrue(AgentNotices.claim("c1", answer, Source.App, now = 1_000))
        assertFalse("das NAS meldet dieselbe Antwort", AgentNotices.claim("c1", answer, Source.Nas, now = 2_500))
        AgentNotices.reset()
        assertTrue(AgentNotices.claim("c1", answer, Source.Nas, now = 1_000))
        assertFalse("die App meldet dieselbe Antwort", AgentNotices.claim("c1", answer, Source.App, now = 1_800))
    }

    @Test
    fun otherChatsAndLaterAnswersRingAgain() {
        assertTrue(AgentNotices.claim("c1", answer, Source.App, now = 1_000))
        assertTrue(AgentNotices.claim("c2", answer, Source.Nas, now = 1_100))
        // Aus der Mitteilung geantwortet: die nächste Antwort derselben Stelle klingelt.
        assertTrue(AgentNotices.claim("c1", "Erledigt.", Source.App, now = 9_000))
        // Auch dieselbe Antwort noch einmal von derselben Stelle (zweimal dasselbe gefragt).
        assertTrue(AgentNotices.claim("c1", "Erledigt.", Source.App, now = 12_000))
    }

    @Test
    fun aLateNasNoteWithTheCutTextStaysQuiet() {
        val long = "Gern. " + "Das NAS läuft normal. ".repeat(20)
        assertTrue(AgentNotices.claim("c1", long, Source.App, now = 0))
        // Ohne Dauerverbindung kommt das NAS erst mit dem Abruf, gekürzt auf 179 Zeichen und „…“.
        val cut = long.take(179) + "…"
        assertFalse(AgentNotices.claim("c1", cut, Source.Nas, now = 14 * 60_000L))
        // Ein anderer Text nach der Laufzeit ist eine andere Antwort.
        assertTrue(AgentNotices.claim("c1", "Wartet auf deine Freigabe", Source.Nas, now = 15 * 60_000L))
    }

    @Test
    fun aPrefixIsNotTheSameAnswerUnlessCut() {
        assertTrue(AgentNotices.claim("c1", "OK.", Source.App, now = 0))
        assertTrue(AgentNotices.claim("c1", "OK. Erledigt.", Source.Nas, now = 60_000))
    }

    @Test
    fun afterTheChatWasSeenTheNextRunRingsFromEitherSide() {
        assertTrue(AgentNotices.claim("c1", answer, Source.App, now = 1_000))
        // Chat geöffnet, neue Frage, wieder weg: die Antwort kommt diesmal zuerst vom NAS.
        AgentNotices.seen("c1")
        assertTrue(AgentNotices.claim("c1", "Neue Antwort.", Source.Nas, now = 10_000))
        assertFalse(AgentNotices.claim("c1", "Neue Antwort.", Source.App, now = 10_500))
    }
}
