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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

/** So viele Plätze zeigt das Dock auf einmal (weitere auf den nächsten Seiten). */
const val DOCK_PER_PAGE = 5

/** Lage des Docks im Fenster und welche Seite zu sehen ist (für das Ablegen von der Startseite). */
class DockGeometry {
    var rect: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
    var page: Int = 0
    var count: Int = 0

    /**
     * Platz unter [window] (Fensterkoordinaten, etwas über dem Dock zählt mit),
     * sonst null: auf der sichtbaren Seite der Platz unter dem Finger, höchstens
     * hinter dem letzten.
     */
    fun slotAt(window: androidx.compose.ui.geometry.Offset, reach: Float): Int? {
        if (rect.width <= 0f) return null
        if (window.x < rect.left || window.x > rect.right || window.y < rect.top - reach || window.y > rect.bottom + reach) return null
        val per = rect.width / DOCK_PER_PAGE
        val i = ((window.x - rect.left) / per).toInt().coerceIn(0, DOCK_PER_PAGE - 1)
        return (page * DOCK_PER_PAGE + i).coerceAtMost(count)
    }
}

/**
 * Das Dock (PLAN 117, Entwürfe 02 und 06): schwebende Leiste. Bis zu fünf
 * Plätze stehen nebeneinander; mehr (1.02, bis 15) stehen auf Seiten zu je
 * fünf, die man zur Seite wischt, mit Punkten darunter („Seite 1 von 3“), der
 * gewählte Platz ist immer zu sehen. Der gewählte Platz hat einen Punkt
 * darunter und fette Schrift (nie nur Farbe), Mitteilungen eine Zahl.
 * [labels] aus: nur Symbole. [dropSlot]: beim Ziehen von der Startseite ein
 * Platzhalter an dieser Stelle.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DockBar(
    items: List<DockItem>,
    selected: String?,
    labels: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    dropSlot: Int? = null,
    geometry: DockGeometry? = null,
) {
    val c = Titan.colors
    val measurer = rememberTextMeasurer()
    val normal = TitanType.nav.copy(fontWeight = FontWeight.SemiBold)
    val small = normal.copy(fontSize = 11.sp, lineHeight = 14.sp)
    val dockName = stringResource(R.string.ui_dock)
    val ghost = DockItem(DROP_KEY, "", androidx.compose.ui.graphics.vector.ImageVector.Builder("drop", 1.dp, 1.dp, 1f, 1f).build())
    val shown = if (dropSlot != null) items.toMutableList().also { it.add(dropSlot.coerceIn(0, it.size), ghost) } else items
    val paged = shown.size > DOCK_PER_PAGE
    val pages = if (paged) shown.chunked(DOCK_PER_PAGE) else listOf(shown)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(26.dp)
        BoxWithConstraints(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = .3f), spotColor = Color.Black.copy(alpha = .3f))
                .clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .then(if (geometry != null) Modifier.onGloballyPositioned { geometry.rect = it.boundsInWindow(); geometry.count = items.size } else Modifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            val gap = with(LocalDensity.current) { 6.dp.toPx() }
            // Mit Seiten hat jeder Platz ein Fünftel: nur ganze Plätze, nichts angeschnitten.
            val per = constraints.maxWidth.toFloat() / (if (paged) DOCK_PER_PAGE else maxOf(1, shown.size)) - gap
            fun widest(style: TextStyle) = items.flatMap { io.github.ra5on.titanos.ui.hyphenPieces(it.label) }
                .maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width.toFloat() } ?: 0f
            val fit = if (labels) dockLabels(per, widest(normal), widest(small)) else DockLabels.None
            val style = if (fit == DockLabels.Small) small else normal
            val row: @Composable (List<DockItem>) -> Unit = { list ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    for (item in list) {
                        if (item.key == DROP_KEY) {
                            DropSlot(Modifier.weight(1f))
                        } else {
                            DockTab(item, item.key == selected, style, fit != DockLabels.None, Modifier.weight(1f)) { onSelect(item.key) }
                        }
                    }
                    if (paged) repeat(DOCK_PER_PAGE - list.size) { Box(Modifier.weight(1f)) }
                }
            }
            if (!paged) {
                Box(Modifier.semantics { contentDescription = dockName }) { row(shown) }
            } else {
                val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
                geometry?.page = pager.currentPage
                // Der gewählte Platz ist immer zu sehen.
                val at = shown.indexOfFirst { it.key == selected }
                androidx.compose.runtime.LaunchedEffect(at / DOCK_PER_PAGE, at >= 0) { if (at >= 0) pager.animateScrollToPage(at / DOCK_PER_PAGE) }
                val pageLabel = stringResource(R.string.ui_dock_page, pager.currentPage + 1, pages.size)
                Column(Modifier.semantics { contentDescription = dockName; stateDescription = pageLabel }) {
                    androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth(), key = { it }) { i -> row(pages[i]) }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) {
                        for (i in pages.indices) {
                            val on = i == pager.currentPage
                            Box(Modifier.padding(horizontal = 3.dp).size(if (on) 7.dp else 5.dp).clip(CircleShape).background(if (on) c.text else c.muted.copy(alpha = .6f)))
                        }
                    }
                }
            }
        }
    }
}

private const val DROP_KEY = "\u0000drop"

/** Platzhalter im Dock beim Ziehen von der Startseite. */
@Composable
private fun DropSlot(modifier: Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp).clip(shape).background(c.accent.copy(alpha = .16f)).border(2.dp, c.accent, shape))
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
