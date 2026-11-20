package io.github.ra5on.titanos.agent.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.ui.generated.TitanColors
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/** Schrift der Antworten: etwas größer als der Grundtext, wie im Web-Chat. */
internal val AnswerStyle = TitanType.body.copy(fontSize = 16.sp, lineHeight = 23.sp)

/** Ein Text mit Auszeichnung: fett, kursiv, Code, Links (in der Akzentfarbe und unterstrichen). */
fun inlineText(text: String, c: TitanColors): AnnotatedString = buildAnnotatedString {
    for (s in parseInline(text)) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) TitanFonts.mono else null,
            background = if (s.code) c.chip else Color.Unspecified,
            fontSize = if (s.code) 14.5.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        if (s.link != null) {
            withLink(LinkAnnotation.Url(s.link, TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(s.text) }
            }
        } else {
            withStyle(style) { append(s.text) }
        }
    }
}

/** Eine Antwort des Agenten als Markdown (chat/Markdown.svelte im Web). */
@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier, onCopied: () -> Unit = {}) {
    val blocks = remember(source) { parseMarkdown(source) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) MdBlockView(b, onCopied)
    }
}

@Composable
private fun MdBlockView(b: MdBlock, onCopied: () -> Unit) {
    val c = Titan.colors
    when (b) {
        is MdBlock.Heading -> Text(
            inlineText(b.text, c),
            style = AnswerStyle.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = when (b.level) { 1 -> 21.sp; 2 -> 19.sp; else -> 17.sp },
                lineHeight = when (b.level) { 1 -> 27.sp; 2 -> 25.sp; else -> 23.sp },
            ),
            color = c.text,
            modifier = Modifier.padding(top = 4.dp),
        )
        is MdBlock.Paragraph -> Text(inlineText(b.text, c), style = AnswerStyle, color = c.text)
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(3.dp).heightIn(min = 22.dp).background(c.border))
            Text(inlineText(b.text, c), style = AnswerStyle, color = c.muted, modifier = Modifier.padding(start = 12.dp))
        }
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 1.dp, max = 1.dp).background(c.border))
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var n = b.start
            for (item in b.items) {
                val mark = when {
                    item.checked == true -> "☑"
                    item.checked == false -> "☐"
                    b.ordered && item.depth == 0 -> "${n++}."
                    item.depth == 0 -> "•"
                    else -> "◦"
                }
                val said = when (item.checked) {
                    true -> stringResource(R.string.agent_checked)
                    false -> stringResource(R.string.agent_unchecked)
                    null -> null
                }
                Row(Modifier.padding(start = (item.depth * 18).dp)) {
                    Text(
                        mark,
                        style = AnswerStyle,
                        color = c.muted,
                        modifier = Modifier.widthIn(min = 22.dp).then(if (said != null) Modifier.semantics { contentDescription = said } else Modifier),
                    )
                    Text(inlineText(item.text, c), style = AnswerStyle, color = c.text, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        is MdBlock.Code -> CodeBlock(b, onCopied)
        is MdBlock.Table -> TableBlock(b, onCopied)
    }
}

@Composable
private fun CodeBlock(b: MdBlock.Code, onCopied: () -> Unit) {
    val c = Titan.colors
    val clip = LocalClipboardManager.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        Row(
            Modifier.fillMaxWidth().background(c.header).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(b.language.ifEmpty { stringResource(R.string.agent_code) }, style = TitanType.small, color = c.muted, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            CopyButton(stringResource(R.string.agent_copy)) {
                clip.setText(AnnotatedString(b.text))
                onCopied()
            }
        }
        // Code bricht nicht um, er lässt sich seitlich schieben.
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(b.text, style = TextStyle(fontFamily = TitanFonts.mono, fontSize = 13.5.sp, lineHeight = 19.sp), color = c.text, softWrap = false)
        }
    }
}

@Composable
internal fun CopyButton(label: String, onClick: () -> Unit) {
    val c = Titan.colors
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AgentIcons.copy, null, tint = c.muted, modifier = Modifier.size(16.dp))
        Text(label, style = TitanType.small, color = c.muted)
    }
}

/**
 * Tabelle, die sich seitlich schieben lässt. Jede Spalte ist mindestens so
 * breit wie ihr längstes Wort: Zellen brechen nur zwischen Wörtern um, nie
 * mitten im Wort.
 */
@Composable
private fun TableBlock(b: MdBlock.Table, onCopied: () -> Unit) {
    val c = Titan.colors
    val clip = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellStyle = TitanType.body.copy(fontSize = 14.5.sp, lineHeight = 20.sp)
    val headStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    // Je Spalte: das längste Wort (darunter nie) und der ganze Text in einer Zeile.
    val measured: List<Pair<Dp, Dp>> = remember(b) {
        b.header.indices.map { col ->
            val texts = listOf(b.header[col] to headStyle) + b.rows.map { it[col] to cellStyle }
            var minWord = 0
            var whole = 0
            for ((t, st) in texts) {
                val plain = parseInline(t).joinToString("") { it.text }
                plain.split(Regex("\\s+")).forEach { w ->
                    if (w.isNotEmpty()) minWord = maxOf(minWord, measurer.measure(w, st).size.width)
                }
                whole = maxOf(whole, measurer.measure(plain, st).size.width)
            }
            with(density) { (minWord.toDp() + 1.dp) to (whole.toDp() + 1.dp) }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = tableWidths(measured, maxWidth - 2.dp - 24.dp * measured.size)
        Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape).horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.background(c.header)) {
                    b.header.forEachIndexed { i, h ->
                        Text(inlineText(h, c), style = headStyle, color = c.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).width(widths[i]))
                    }
                }
                for (row in b.rows) {
                    Box(Modifier.heightIn(min = 1.dp, max = 1.dp).background(c.border).width(widths.fold(0.dp) { a, w -> a + w + 24.dp }))
                    Row {
                        row.forEachIndexed { i, cell ->
                            Text(inlineText(cell, c), style = cellStyle, color = c.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).width(widths[i]))
                        }
                    }
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CopyButton(stringResource(R.string.agent_table_copy)) {
                val text = (listOf(b.header) + b.rows).joinToString("\n") { it.joinToString("\t") }
                clip.setText(AnnotatedString(text))
                onCopied()
            }
        }
    }
}

/**
 * Breiten der Spalten: passt alles in einer Zeile, so breit wie der Text.
 * Sonst bekommt jede Spalte ihr längstes Wort und den Rest des Platzes
 * anteilig, damit Zellen nur zwischen Wörtern umbrechen. Reicht selbst das
 * nicht, gelten die Wortbreiten und die Tabelle lässt sich seitlich schieben.
 */
internal fun tableWidths(cols: List<Pair<Dp, Dp>>, available: Dp): List<Dp> {
    val full = cols.fold(0.dp) { a, c -> a + maxOf(c.first, c.second) }
    if (full <= available) return cols.map { maxOf(it.first, it.second) }
    val words = cols.fold(0.dp) { a, c -> a + c.first }
    if (words >= available) return cols.map { it.first }
    val extra = available - words
    val wish = cols.fold(0f) { a, c -> a + (maxOf(c.first, c.second) - c.first).value }
    return cols.map { (word, whole) ->
        val want = (maxOf(word, whole) - word).value
        word + if (wish > 0f) extra * (want / wish) else 0.dp
    }
}
