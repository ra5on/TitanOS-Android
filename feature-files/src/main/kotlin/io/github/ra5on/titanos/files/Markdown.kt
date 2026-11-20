package io.github.ra5on.titanos.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/** Ein Block aus Markdown, so weit der Betrachter ihn zeigt. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Einfaches Markdown: Überschriften, Absätze, Listen, Zitate, Code und Trennlinien. */
object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(source: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        val lines = source.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~") -> {
                    flush()
                    val fence = line.trimStart().take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    out += MdBlock.Code(code.toString().trimEnd('\n'))
                }
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                heading.matches(line) -> {
                    flush()
                    val m = heading.find(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                    out += MdBlock.Bullet(marker, m.groupValues[3], m.groupValues[1].length / 2)
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    out += MdBlock.Quote(line.trimStart().removePrefix(">").trim())
                }
                line.startsWith("    ") || line.startsWith("\t") -> {
                    flush()
                    out += MdBlock.Code(line.removePrefix("    ").removePrefix("\t"))
                }
                else -> para.append(line.trim()).append(' ')
            }
            i++
        }
        flush()
        // Aufeinanderfolgende Zitat- und Codezeilen zusammenfassen.
        val merged = ArrayList<MdBlock>()
        for (b in out) {
            val last = merged.lastOrNull()
            when {
                b is MdBlock.Quote && last is MdBlock.Quote -> merged[merged.lastIndex] = MdBlock.Quote(last.text + "\n" + b.text)
                else -> merged += b
            }
        }
        return merged
    }

    /** Fett, kursiv, `Code` und [Links](…) innerhalb einer Zeile. */
    fun inline(text: String, codeBg: androidx.compose.ui.graphics.Color, link: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val end = text.indexOf(rest.take(2), i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), codeBg, link)) }
                        i = end + 2
                    } else { append(rest.take(2)); i += 2 }
                }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 1 && !rest[1].isWhitespace() -> {
                    val end = text.indexOf(rest[0], i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), codeBg, link)) }
                        i = end + 1
                    } else { append(rest[0]); i++ }
                }
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append('`'); i++ }
                }
                rest.startsWith("[") -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > 0) text.indexOf(')', close) else -1
                    if (close > i && end > close) {
                        withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, close)) }
                        append(" (")
                        append(text.substring(close + 2, end))
                        append(")")
                        i = end + 1
                    } else { append('['); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

/** Markdown formatiert; Text bricht um, Codeblöcke scrollen seitlich. */
@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val codeBg = c.chip
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in Markdown.parse(source)) {
            when (b) {
                is MdBlock.Heading -> Text(
                    Markdown.inline(b.text, codeBg, c.accent),
                    style = when (b.level) {
                        1 -> TitanType.big.copy(fontSize = 26.sp, lineHeight = 32.sp)
                        2 -> TitanType.title
                        3 -> TitanType.topTitle
                        else -> TitanType.rowTitle
                    },
                    color = c.text,
                    modifier = Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp),
                )
                is MdBlock.Paragraph -> Text(Markdown.inline(b.text, codeBg, c.accent), style = TitanType.body, color = c.text)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(b.marker, style = TitanType.body, color = c.muted, modifier = Modifier.width(26.dp))
                    Text(Markdown.inline(b.text, codeBg, c.accent), style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
                    Text(Markdown.inline(b.text, codeBg, c.accent), style = TitanType.body, color = c.muted, modifier = Modifier.padding(start = 10.dp))
                }
                is MdBlock.Code -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(b.text, style = TitanType.small.copy(fontFamily = FontFamily.Monospace), color = c.text)
                }
                MdBlock.Rule -> Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(c.border))
            }
        }
    }
}
