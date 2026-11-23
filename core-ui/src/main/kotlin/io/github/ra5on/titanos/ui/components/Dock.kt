package io.github.ra5on.titanos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.ui.R
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/** Ein Platz im Dock: Schlüssel, Name, Strichsymbol und wahlweise eine Zahl (ungelesene Mitteilungen). */
data class DockItem(val key: String, val label: String, val icon: ImageVector, val badge: Int = 0)

/**
 * Wie die Namen im Dock stehen: in normaler oder kleiner Schrift, oder gar
 * nicht, wenn selbst die kleine nicht passt (sehr große Schrift). Dann bleiben
 * Symbole mit ihrem Namen für TalkBack; nichts wird abgeschnitten und kein
 * Platz verschwindet hinter „Mehr“ (Start muss immer da sein).
 */
enum class DockLabels { Normal, Small, None }

fun dockLabels(perItem: Float, widestNormal: Float, widestSmall: Float): DockLabels = when {
    widestNormal <= perItem -> DockLabels.Normal
    widestSmall <= perItem -> DockLabels.Small
    else -> DockLabels.None
}

/**
 * Das Dock (PLAN 117, Entwürfe 02 und 06): schwebende Leiste mit bis zu fünf
 * Plätzen. Der gewählte Platz hat einen Punkt darunter und fette Schrift (nie
 * nur Farbe), Mitteilungen eine Zahl. [labels] aus: nur Symbole.
 */
@Composable
fun DockBar(items: List<DockItem>, selected: String?, labels: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val measurer = rememberTextMeasurer()
    val normal = TitanType.nav.copy(fontWeight = FontWeight.SemiBold)
    val small = normal.copy(fontSize = 11.sp, lineHeight = 14.sp)
    val dockName = stringResource(R.string.ui_dock)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(26.dp)
        BoxWithConstraints(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = .3f), spotColor = Color.Black.copy(alpha = .3f))
                .clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .semantics { contentDescription = dockName }
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            val gap = with(LocalDensity.current) { 6.dp.toPx() }
            val per = constraints.maxWidth.toFloat() / maxOf(1, items.size) - gap
            fun widest(style: TextStyle) = items.flatMap { io.github.ra5on.titanos.ui.hyphenPieces(it.label) }
                .maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width.toFloat() } ?: 0f
            val fit = if (labels) dockLabels(per, widest(normal), widest(small)) else DockLabels.None
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                for (item in items) {
                    DockTab(item, item.key == selected, if (fit == DockLabels.Small) small else normal, fit != DockLabels.None, Modifier.weight(1f)) { onSelect(item.key) }
                }
            }
        }
    }
}

@Composable
private fun DockTab(item: DockItem, on: Boolean, style: TextStyle, showLabel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Titan.colors
    val unread = if (item.badge > 0) pluralStringResource(R.plurals.ui_unread, item.badge, item.badge) else null
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = item.label.replace(io.github.ra5on.titanos.ui.SOFT_HYPHEN.toString(), "")
                selected = on
                stateDescription = listOfNotNull(if (on) "✓" else null, unread).joinToString(", ")
            }
            .heightIn(min = 48.dp).padding(top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(40.dp, 28.dp), contentAlignment = Alignment.Center) {
            Icon(item.icon, null, tint = if (on) c.text else c.muted, modifier = Modifier.size(24.dp))
            if (item.badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                        .clip(CircleShape).background(c.statusError).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (item.badge > 99) "99+" else item.badge.toString(), style = TitanType.small.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold), color = Color.White)
                }
            }
        }
        if (showLabel) {
            io.github.ra5on.titanos.ui.HyphenText(
                item.label,
                (if (on) style else style.copy(fontWeight = FontWeight.Medium)).copy(hyphens = Hyphens.None, lineBreak = LineBreak.Heading),
                if (on) c.text else c.muted,
                Modifier.padding(horizontal = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
        // Punkt unter dem gewählten Platz (wie im Entwurf), der Platz bleibt auch ohne ihn gleich hoch.
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) c.accent else Color.Transparent))
    }
}
