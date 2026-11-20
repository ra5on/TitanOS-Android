package io.github.ra5on.titanos.agent.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun headingsParagraphsAndRule() {
        val b = parseMarkdown("# Titel\n\nErster Satz\nzweite Zeile\n\n---\n\nEnde")
        assertEquals(MdBlock.Heading(1, "Titel"), b[0])
        assertEquals(MdBlock.Paragraph("Erster Satz\nzweite Zeile"), b[1])
        assertEquals(MdBlock.Rule, b[2])
        assertEquals(MdBlock.Paragraph("Ende"), b[3])
    }

    @Test
    fun listsWithNestingNumbersAndTasks() {
        val b = parseMarkdown("- eins\n  - unter\n- [x] fertig\n\n3. drei\n4. vier")
        val bullets = b[0] as MdBlock.ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(listOf(MdItem("eins"), MdItem("unter", 1), MdItem("fertig", 0, true)), bullets.items)
        val numbers = b[1] as MdBlock.ListBlock
        assertEquals(true, numbers.ordered)
        assertEquals(3, numbers.start)
        assertEquals(listOf("drei", "vier"), numbers.items.map { it.text })
    }

    @Test
    fun codeFenceWithLanguageAndUnclosedFenceWhileStreaming() {
        val b = parseMarkdown("Vorher\n```bash\nls -l\necho hi\n```\nNachher")
        assertEquals(MdBlock.Code("bash", "ls -l\necho hi"), b[1])
        assertEquals(MdBlock.Paragraph("Nachher"), b[2])
        val streaming = parseMarkdown("```python\nprint(1)")
        assertEquals(MdBlock.Code("python", "print(1)"), streaming.single())
    }

    @Test
    fun tableWithEscapedPipeAndShortRows() {
        val b = parseMarkdown("| Installation | Zustand |\n|---|:---:|\n| Docker-App | läuft |\n| VM \\| HAOS |\n")
        val t = b.single() as MdBlock.Table
        assertEquals(listOf("Installation", "Zustand"), t.header)
        assertEquals(listOf(listOf("Docker-App", "läuft"), listOf("VM | HAOS", "")), t.rows)
    }

    @Test
    fun quote() {
        assertEquals(MdBlock.Quote("Zitat\nweiter"), parseMarkdown("> Zitat\n> weiter").single())
    }

    @Test
    fun inlineBoldItalicCodeAndLinks() {
        val s = parseInline("Der Elternabend ist **heute um 19:00**, *bitte* `pünktlich` – [Plan](https://x.test/p) oder https://nas.example/a.")
        assertEquals("Der Elternabend ist ", s[0].text)
        assertEquals(MdSpan("heute um 19:00", bold = true), s[1])
        assertTrue(s.any { it == MdSpan("bitte", italic = true) })
        assertTrue(s.any { it == MdSpan("pünktlich", code = true) })
        assertTrue(s.any { it == MdSpan("Plan", link = "https://x.test/p") })
        assertTrue(s.any { it == MdSpan("https://nas.example/a", link = "https://nas.example/a") })
        assertEquals(".", s.last().text)
    }

    @Test
    fun underscoresInsideWordsStayAndUnclosedMarksArePlain() {
        assertEquals(listOf(MdSpan("datei_name_neu.txt und 3 * 4")), parseInline("datei_name_neu.txt und 3 * 4"))
        assertEquals(listOf(MdSpan("**offen")), parseInline("**offen"))
        assertEquals(listOf(MdSpan("a*b")), parseInline("a\\*b"))
    }
}

class TableWidthsTest {
    private fun dp(v: Int) = androidx.compose.ui.unit.Dp(v.toFloat())

    @Test
    fun fitsInOneLine() {
        assertEquals(listOf(dp(50), dp(60)), tableWidths(listOf(dp(20) to dp(50), dp(30) to dp(60)), dp(200)))
    }

    @Test
    fun wrapsOnlyBetweenWordsWhenTooWide() {
        val w = tableWidths(listOf(dp(20) to dp(120), dp(30) to dp(30)), dp(100))
        assertEquals(dp(70), w[0])
        assertEquals(dp(30), w[1])
    }

    @Test
    fun scrollsWhenWordsDoNotFit() {
        assertEquals(listOf(dp(80), dp(90)), tableWidths(listOf(dp(80) to dp(200), dp(90) to dp(100)), dp(100)))
    }
}
