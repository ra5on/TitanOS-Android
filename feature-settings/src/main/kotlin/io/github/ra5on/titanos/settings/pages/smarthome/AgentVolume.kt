package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private fun finished(m: JsonObject?) = m == null || m.str("state") == "done" || m.str("state") == "failed"

private fun kindLabel(tr: Tr, v: JsonObject) = v.str("kind").takeIf { it.isNotEmpty() }?.let { tr.or("agent.vol.kind.$it", it) }.orEmpty()

private fun freeText(tr: Tr, v: JsonObject) =
    tr.t("agent.vol.free", "free" to Fmt.bytes(tr, v.long("freeBytes")), "size" to Fmt.bytes(tr, v.long("sizeBytes"))) +
        kindLabel(tr, v).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()

/**
 * Wo der KI-Agent alles ablegt (AgentVolume.svelte, PLAN 58): gewählt beim
 * Einschalten, später mit „Umziehen“; titand kopiert, prüft, schaltet um und
 * entfernt erst dann die alten Dateien.
 */
@Composable
internal fun AgentVolumeGroup(st: AgentState) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf(listOf<JsonObject>()) }
    var move by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    val current = st.settings?.str("volume").orEmpty()

    suspend fun load() {
        try {
            val r = env.api.obj("agent/volumes")
            volumes = r.a("volumes").objects()
            move = r.o("move").takeIf { r.has("move") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    LaunchedEffect(round) { load() }
    // Ein laufender Umzug wird jede Sekunde nachgesehen.
    val running = move != null && !finished(move)
    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            val m = runCatching { env.api.obj("agent/volume/move") }.getOrNull() ?: continue
            move = m.o("move").takeIf { m.has("move") }
            if (finished(move)) {
                if (move?.str("state") == "done") {
                    val to = move?.str("to").orEmpty()
                    st.savedByNAS { it.with("volume" to to) }
                    load()
                }
                break
            }
        }
    }

    fun list(items: List<JsonObject>) = items.map { v ->
        Triple(v.str("name"), v.str("name") + if (v.str("kind") == "ssd") " · " + tr.t("agent.vol.recommended") else "", freeText(tr, v))
    }

    // Die Wahl beim Einschalten (PLAN 58).
    if (st.choosingVolume) {
        LaunchedEffect(Unit) {
            st.choosingVolume = false
            ov.show { close ->
                VolumeSheet(
                    title = t("agent.vol.chooseTitle"),
                    text = t("agent.vol.chooseText"),
                    items = volumes,
                    start = (volumes.firstOrNull { it.str("kind") == "ssd" } ?: volumes.firstOrNull())?.str("name").orEmpty(),
                    action = t("agent.vol.take"),
                    empty = t("agent.vol.noneMounted"),
                    list = ::list,
                    close = close,
                ) { pick -> st.edit { it.with("volume" to pick, "enabled" to true) } }
            }
        }
    }

    val here = volumes.firstOrNull { it.str("name") == current }
    val others = volumes.filter { it.str("name") != current }
    SettingGroup(t("agent.vol.title"), desc = t("agent.vol.intro")) {
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
        val m = move
        if (running && m != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Plain(tr.t("agent.vol.state.${m.str("state")}", "from" to m.str("from"), "to" to m.str("to")))
                val total = m.long("total")
                val pct = if (total > 0) (m.long("bytes").toFloat() / total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { pct }, color = Titan.colors.accent, trackColor = Titan.colors.track, modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr.t("agent.vol.progress") })
                Muted(tr.t("agent.vol.copied", "files" to m.long("files"), "bytes" to Fmt.bytes(tr, m.long("bytes")), "total" to Fmt.bytes(tr, total)))
            }
        } else {
            if (m?.str("state") == "done") {
                var text = tr.t("agent.vol.done", "to" to m.str("to"))
                if (m.long("skipped") > 0) text += " " + tr.t("agent.vol.skipped", "n" to m.long("skipped"))
                if (m.str("cleanup").isNotEmpty()) text += " " + tr.t("agent.vol.cleanup", "from" to m.str("from"))
                Banner(text, tone = Tone.Ok)
            } else if (m?.str("state") == "failed") {
                Banner(tr.t("agent.vol.failed", "from" to m.str("from"), "error" to m.str("error")), tone = Tone.Warn)
            }
            SettingRow(
                here?.str("name") ?: current.ifEmpty { tr.t("agent.vol.title") },
                desc = if (here != null) freeText(tr, here) else if (current.isNotEmpty()) tr.t("agent.vol.missing") else tr.t("agent.vol.none"),
                icon = WebIcons["disk"],
                control = if (current.isNotEmpty() && others.isNotEmpty()) ({
                    SmallButton(t("agent.vol.move"), {
                        ov.show { close ->
                            VolumeSheet(
                                title = t("agent.vol.moveTitle"),
                                text = tr.t("agent.vol.moveText", "from" to current),
                                items = others,
                                start = others.first().str("name"),
                                action = t("agent.vol.moveStart"),
                                empty = t("agent.vol.noneMounted"),
                                list = ::list,
                                close = close,
                            ) { pick ->
                                Guard.require(context, GuardAction.Settings) {
                                    scope.launch {
                                        error = ""
                                        try {
                                            move = env.api.post("agent/volume/move", jobj("to" to pick)).o().o("move")
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = tr.errorOf(e)
                                        }
                                    }
                                }
                            }
                        }
                    })
                }) else null,
            )
            for (v in volumes.filter { it.bool("oldCopy") }) {
                SettingRow(tr.t("agent.vol.oldTitle", "volume" to v.str("name")), desc = t("agent.vol.oldText"), icon = WebIcons["archive"], control = {
                    SmallButton(t("agent.vol.oldRemove"), {
                        ov.confirm(tr.t("agent.vol.oldRemove"), tr.t("agent.vol.oldConfirm", "volume" to v.str("name")), action = tr.t("agent.vol.oldRemove"), cancel = tr.t("common.cancel")) {
                            Guard.require(context, GuardAction.Settings) {
                                scope.launch {
                                    error = ""
                                    try {
                                        env.api.post("agent/volume/cleanup", jobj("volume" to v.str("name")))
                                        round++
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = tr.errorOf(e)
                                    }
                                }
                            }
                        }
                    })
                })
            }
        }
    }
}

@Composable
private fun BoxScope.VolumeSheet(
    title: String,
    text: String,
    items: List<JsonObject>,
    start: String,
    action: String,
    empty: String,
    list: (List<JsonObject>) -> List<Triple<String, String, String?>>,
    close: () -> Unit,
    onPick: (String) -> Unit,
) {
    var pick by remember { mutableStateOf(start) }
    DialogSheet(
        title,
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("common.cancel")) { close() },
            SheetButton(action, primary = true, enabled = pick.isNotEmpty()) { close(); onPick(pick) },
        ),
    ) {
        Plain(text)
        if (items.isEmpty()) Banner(empty, tone = Tone.Warn) else {
            RadioList(pick, list(items), { pick = it })
            Muted(t("agent.vol.hint"))
        }
    }
}
