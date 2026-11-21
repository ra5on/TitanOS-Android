package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Die Reiter (PLAN 53/73): die Übersicht führt zu jedem Bereich. */
private val TABS = listOf("overview", "connection", "rights", "accounts", "phone", "ha", "initiative", "instruction", "features")
private val AREA_ICON = mapOf(
    "overview" to "sparkle", "connection" to "globe", "rights" to "shield", "accounts" to "users", "phone" to "monitor",
    "ha" to "home", "initiative" to "zap", "instruction" to "file", "features" to "sparkle",
)

/**
 * KI-Agent (routes/settings/agent, PLAN 44): Ollama oder ein Anbieter,
 * Modell, Denkstufe und Kontext, wer den Agenten nutzt, Orte, Rechte,
 * Handy-App, Home Assistant mit Assist, Eigeninitiative, Anweisung und die
 * Funktionen. Nur für Besitzer und Administratoren.
 */
@Composable
fun AgentPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember(env, tr) { AgentState(env, tr, scope) }
    LaunchedEffect(st) { st.load(); st.loadOnce() }
    val tab = a.tab?.takeIf { it in TABS } ?: "overview"

    // Ein anderer Bereich dieser Seite behält die Änderungen; zurück geht zur Übersicht.
    BackHandler(enabled = tab != "overview") { a.tab = null }
    // Die Seite mit ungespeicherten Änderungen verlassen fragt erst (NavigationGuard).
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var leaving by remember { mutableStateOf(false) }
    BackHandler(enabled = tab == "overview" && st.dirty && !st.saving && !leaving) {
        ov.confirm(tr.t("ops.unsaved"), tr.t("agent.unsavedLeave"), action = tr.t("ops.discard"), cancel = tr.t("common.cancel")) {
            leaving = true
            scope.launch { delay(50); back?.onBackPressed() }
        }
    }

    if (tab == "overview") {
        PageIntro(t("agent.settingsIntro"))
    } else {
        // Der offene Bereich als letzter Teil des Pfads; „KI-Agent“ führt zur Übersicht.
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { heading() },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                t("agent.settingsTitle"),
                style = TitanType.rowTitle,
                color = Titan.colors.muted,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { a.tab = null }.padding(vertical = 6.dp, horizontal = 2.dp),
            )
            Text("›", style = TitanType.rowTitle, color = Titan.colors.muted)
            Text(t("agent.tab.$tab"), style = TitanType.rowTitle, color = Titan.colors.text)
        }
    }

    if (st.error.isNotEmpty()) Banner(st.error, tone = Tone.Error, action = if (st.settings == null) t("common.retry") else null, onAction = { st.load() })
    val s = st.settings
    if (s == null) {
        if (st.loading) LoadingLine(t("common.loading"))
        return
    }

    @Composable
    fun SaveBar() {
        if (st.dirty || st.saving) {
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(12.dp)).background(Titan.colors.surface).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Muted(t("agent.unsaved"), Modifier.weight(1f))
                SmallButton(t("common.cancel"), { st.load() }, enabled = !st.saving)
                SmallButton(t("common.save"), { Guard.require(context, GuardAction.Settings) { st.save() } }, kind = ButtonKind.Primary, enabled = !st.saving)
            }
        } else if (st.saved) {
            StatusChip(t("agent.saved"), Tone.Ok, Modifier.padding(vertical = 8.dp))
        }
    }

    if (tab != "overview" && tab != "features") SaveBar()

    when (tab) {
        "features" -> AgentFeaturesTab()
        "overview" -> {
            SettingGroup {
                SettingRow(
                    t(if (s.bool("enabled")) "agent.heroStateOn" else "agent.heroStateOff"),
                    desc = when {
                        st.status?.bool("reachable") == true -> tr.t("agent.heroOn", "version" to st.status?.str("version").orEmpty(), "model" to s.str("model").ifEmpty { "–" })
                        s.bool("enabled") -> tr.t("agent.heroDown")
                        else -> tr.t("agent.heroOff")
                    },
                    control = {
                        AppIcon("agent", "violet", 36.dp)
                        OnOff(s.bool("enabled"), { on ->
                            // PLAN 58: das Einschalten fragt einmal, wohin die Daten gehen.
                            if (on && s.str("volume").isEmpty()) st.choosingVolume = true else st.edit { it.with("enabled" to on) }
                        }, label = t("agent.enabled"))
                    },
                )
            }
            AgentVolumeGroup(st)
            SettingGroup(t("agent.areasTitle")) {
                for (id in TABS.drop(1)) {
                    SettingRow(
                        t("agent.tab.$id"),
                        desc = t("agent.areaHint.$id"),
                        icon = WebIcons[AREA_ICON[id] ?: "gear"],
                        summary = summary(st, id, tr).ifEmpty { null },
                        onClick = { a.tab = id },
                    )
                }
            }
        }
        "phone" -> PhoneTab(st)
        "connection" -> AgentConnectionTab(st)
        "instruction" -> SettingGroup(t("agent.sectionInstruction"), desc = t("agent.instructionIntro")) {
            Field(
                s.str("instruction"),
                { v -> st.edit { it.with("instruction" to v.take(8000)) } },
                label = t("agent.sectionInstruction"),
                minHeight = 180.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Muted(t("agent.instructionHint"))
            if (s.str("instruction").isBlank()) {
                SmallButton(t("agent.instructionUseTemplate"), { st.edit { it.with("instruction" to tr.t("agent.instructionTemplate")) } })
            }
        }
        "accounts" -> AgentAccountsTab(st)
        "rights" -> AgentRightsTab(st)
        "initiative" -> SettingGroup(t("agent.sectionInitiative")) {
            SettingRow(t("agent.react"), desc = t("agent.reactHint"), icon = WebIcons["zap"], control = {
                OnOff(s.bool("react"), { v -> st.edit { it.with("react" to v) } }, label = t("agent.react"))
            })
            // 0.95: „Wohnzimmerlicht an“ schaltet ohne das Modell.
            SettingRow(t("agent.quick"), desc = t("agent.quickHint"), icon = WebIcons["zap"], control = {
                OnOff(!s.bool("quickOff"), { v -> st.edit { it.with("quickOff" to !v) } }, label = t("agent.quick"))
            })
            SettingRow(t("agent.weeklyReport"), icon = WebIcons["file"], control = {
                OnOff(s.bool("weeklyReport"), { v -> st.edit { it.with("weeklyReport" to v) } }, label = t("agent.weeklyReport"))
            })
            SettingRow(t("agent.reactAs"), icon = WebIcons["users"], control = {
                val admins = st.accounts.filter { it.str("role") == "owner" || it.str("role") == "admin" }.map { it.str("name") }
                val cur = s.str("reactAs")
                val options = listOf("" to t("agent.firstOwner")) + (admins + listOfNotNull(cur.takeIf { it.isNotEmpty() && it !in admins })).map { it to it }
                Select(cur, options, { v -> st.edit { it.with("reactAs" to v) } }, label = t("agent.reactAs"))
            })
        }
        "ha" -> AgentHaTab(st)
    }

    if (tab != "features") SaveBar()
}

private fun summary(st: AgentState, id: String, tr: io.github.ra5on.titanos.ui.i18n.Tr): String {
    val s = st.settings ?: return ""
    return when (id) {
        "connection" -> s.str("model").ifEmpty { "–" }
        "rights" -> tr.t(
            when (s.o("policy").str("preset")) {
                "read" -> "agent.presetRead"
                "standard" -> "agent.presetStandard"
                "all" -> "agent.presetAll"
                else -> "agent.presetCustom"
            },
        )
        "accounts" -> tr.t("agent.summaryAccounts", "count" to s.texts("users").size)
        "phone" -> st.appUrl.let { if (it.startsWith("https:")) "HTTPS" else if (it.isNotEmpty()) "HTTP" else "" }
        "ha" -> tr.t(if (s.o("homeassistant").bool("assist")) "agent.summaryOn" else "agent.summaryOff")
        "instruction" -> if (s.str("instruction").isNotBlank()) tr.t("agent.summarySet") else ""
        else -> ""
    }
}

/** Die Handy-App: erst der Weg zum NAS, dann der QR-Code. */
@Composable
private fun PhoneTab(st: AgentState) {
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    SettingGroup(t("agent.phoneApp"), desc = t("agent.phoneAppIntro")) {
        Legend(t("agent.phoneWay"))
        val options = st.addresses.map { a ->
            Triple(a.str("url"), tr.or("agent.way.${a.str("kind")}", a.str("kind")), a.str("url") + " · " + if (a.bool("https")) "✓ " + tr.t("agent.wayHttps") else tr.t("agent.wayHttp"))
        } + Triple("own", tr.t("agent.way.own"), if (st.way == "own") null else tr.t("agent.wayOwnHint"))
        RadioList(st.way, options, { st.way = it })
        if (st.way == "own") {
            Field(st.own, { st.own = it }, label = t("agent.way.own"), placeholder = "https://nas.example.org", singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        val url = st.appUrl
        SettingRow(t("agent.phoneQrTitle"), desc = t("agent.phoneHttpsHint"), icon = WebIcons["grid"]) {
            if (url.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(url, tr.t("agent.phoneQr", "url" to url))
                    Mono(url.replace(Regex("^https?://"), ""))
                }
            } else {
                Plain(t("agent.phoneNoUrl"), Modifier.padding(vertical = 8.dp))
            }
        }
        if (url.isNotEmpty()) {
            SmallButton(if (copied) "✓ " + t("agent.copied") else t("agent.copyLink"), {
                copied = copyText(context, url)
                scope.launch { delay(1500); copied = false }
            }, icon = WebIcons["copy"])
        }
    }
}

/** QR-Code auf weißem Grund (zxing), mit Beschreibung für Bildschirmleser. */
@Composable
internal fun QrCode(text: String, label: String, sizeDp: Int = 180) {
    val matrix = remember(text) {
        runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2)) }.getOrNull()
    } ?: return
    Canvas(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).semantics { contentDescription = label }) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
