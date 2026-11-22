package io.github.ra5on.titanos.agent.panel

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonObject

/** Öffnet einen Chat in der Ansicht des Agenten (TitanOS-App: das Programm KI-Agent); null: nicht angeboten. */
val LocalAgentOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Breite des Panels als Spalte (ColumnFrame-aside im Web). */
private const val PANEL_DP = 380

/**
 * Rahmen eines Programms mit dem Agent-Panel (Mail, Kontakte, Kalender wie im
 * Web seit 1.13): ein Knopf „KI-Agent“ unten links, wenn das Konto den Agenten
 * hat; offen steht das Panel ab Split als Spalte rechts neben dem Inhalt (der
 * Inhalt sieht dann seine schmalere Breite), am Handy in einem Blatt von
 * unten. Offen oder zu merkt sich das Gerät je Programm.
 */
@Composable
fun AgentPanelFrame(api: AgentApi, app: String, context: JsonObject?, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val tr = remember { Tr.load(ctx) }
    var available by remember(api) { mutableStateOf(false) }
    LaunchedEffect(api) { available = agentAvailable(api) }
    var open by rememberSaveable(app) { mutableStateOf(PanelPrefs.open(ctx, app)) }
    fun setOpen(on: Boolean) {
        open = on
        PanelPrefs.setOpen(ctx, app, on)
    }
    val width = currentWidth()
    val opener = LocalAgentOpener.current
    val column = available && open && width.twoPanes
    Box(Modifier.fillMaxSize()) {
        if (column) {
            Row(Modifier.fillMaxSize()) {
                val cfg = LocalConfiguration.current
                val narrower = remember(cfg) { Configuration(cfg).apply { screenWidthDp = maxOf(320, cfg.screenWidthDp - PANEL_DP - 1) } }
                Box(Modifier.weight(1f).fillMaxHeight()) { CompositionLocalProvider(LocalConfiguration provides narrower) { content() } }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Titan.colors.border))
                AgentPanel(api, app, context, onClose = { setOpen(false) }, modifier = Modifier.width(PANEL_DP.dp).fillMaxHeight().statusBarsPadding(), onOpenInApp = opener)
            }
        } else {
            content()
        }
        if (available && !column && !(open && !width.twoPanes)) {
            val label = tr.t("agentpim.panel.toggle")
            Row(
                Modifier.align(Alignment.BottomStart).zIndex(10f).navigationBarsPadding().padding(12.dp)
                    .shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Titan.colors.surface)
                    .border(1.dp, Titan.colors.border, RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button) { setOpen(true) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WIcon("sparkle", 18.dp, Titan.colors.accent)
                Text(label, style = TitanType.small, color = Titan.colors.text)
            }
        }
        if (available && open && !width.twoPanes) {
            Sheet(onDismiss = { setOpen(false) }) {
                AgentPanel(api, app, context, onClose = { setOpen(false) }, phone = true, onOpenInApp = opener)
            }
        }
    }
}
