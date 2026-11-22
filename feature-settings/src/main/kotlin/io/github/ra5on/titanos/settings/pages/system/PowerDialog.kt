package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Overlays
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Neustart des NAS (PowerDialog.svelte, Aktion „restart“): Bestätigung,
 * Vorbereitung mit der Liste der Gäste, die gestoppt werden, und Warten, bis
 * das NAS wieder antwortet. Nach dem Tippen auf „Neu starten“ der
 * Fingerabdruck (GuardAction.Power).
 */
internal fun Overlays.showRestart(env: SettingsEnv) = show { close -> PowerDialog(env, restartAction = true, close = close) }

@Composable
private fun BoxScope.PowerDialog(env: SettingsEnv, restartAction: Boolean, close: () -> Unit) {
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("confirm") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var afterRebuild by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val preparing = status?.get("preparing") as? JsonObject
    val restart = if (preparing != null) preparing.str("action") == "reboot" else restartAction
    val stuck = preparing?.a("items")?.objects().orEmpty().filter { it.str("kind") != "script" && it.str("state") != "stopped" }

    suspend fun waitForRestart() {
        val started = System.currentTimeMillis()
        var wasDown = false
        while (true) {
            delay(3000)
            try {
                env.api.get("health")
                if (wasDown || System.currentTimeMillis() - started > 30_000) { close(); return }
            } catch (_: Exception) {
                wasDown = true
            }
        }
    }
    fun finish() {
        phase = "done"
        if (restart) scope.launch { waitForRestart() }
    }
    suspend fun load() {
        try {
            status = env.api.obj("system/power")
        } catch (_: Exception) {
            // Während das NAS herunterfährt, enden die Antworten: es ist unterwegs.
            if (phase == "preparing") finish()
            return
        }
        if (status?.get("preparing") is JsonObject && phase == "confirm") phase = "preparing"
        if (phase != "preparing") return
        val p = status?.get("preparing") as? JsonObject
        if (p?.str("phase") == "going" || (p == null && !busy)) finish()
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(phase) {
        while (phase == "preparing") {
            delay(2000)
            load()
        }
    }
    fun confirm() {
        error = ""
        phase = "working"
        val wait = status?.bool("rebuilding") == true && afterRebuild
        scope.launch {
            try {
                val res = env.api.post("system/power", jobj("action" to if (restartAction) "reboot" else "poweroff", "afterRebuild" to wait, "wait" to true)) as? JsonObject
                if (wait && res?.bool("pending") == true) { phase = "waiting"; return@launch }
            } catch (e: Exception) {
                error = tr.errorOf(e); phase = "confirm"; return@launch
            }
            busy = true
            phase = "preparing"
            delay(600)
            busy = false
            load()
        }
    }
    fun anyway() = scope.launch {
        error = ""
        try { env.api.post("system/power/now", JsonObject(emptyMap())) } catch (e: Exception) { error = tr.errorOf(e) }
    }
    fun cancelWaiting() = scope.launch {
        error = ""
        try {
            status = env.api.delete("system/power") as? JsonObject ?: status
            if (phase == "preparing") close()
        } catch (e: Exception) { error = tr.errorOf(e) }
    }

    val title = t(
        when {
            phase == "preparing" -> if (restart) "power.preparingRestart" else "power.preparingShutdown"
            phase != "done" -> if (restart) "power.restartTitle" else "power.shutdownTitle"
            else -> if (restart) "power.restarting" else "power.shuttingDown"
        },
    )
    val locked = phase == "working" || phase == "done" || phase == "preparing"
    val actions = when (phase) {
        "waiting" -> listOf(DialogAction(t("common.close")) { close() })
        "preparing" -> listOfNotNull(
            DialogAction(t("power.callOff")) { cancelWaiting() },
            if (preparing?.str("phase") != "going") DialogAction(t(if (restart) "power.anywayRestart" else "power.anywayShutdown"), danger = true) { anyway() } else null,
        )
        "done" -> emptyList()
        else -> listOf(
            DialogAction(t(if (restartAction) "power.restartAction" else "power.shutdownAction"), danger = true) {
                if (phase != "working") Guard.require(context, GuardAction.Power) { confirm() }
            },
            DialogAction(t("common.cancel")) { if (phase != "working") close() },
        )
    }
    ConfirmDialog(title = title, text = null, actions = actions, onDismiss = { if (!locked) close() }, body = {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                "waiting" -> Body(t(if (restartAction) "power.waitingRestart" else "power.waitingShutdown"))
                "preparing" -> {
                    Muted(t(if (restart) "power.preparingTextRestart" else "power.preparingTextShutdown"))
                    val items = preparing?.a("items")?.objects().orEmpty()
                    if (items.isNotEmpty()) {
                        for (item in items) {
                            val state = item.str("state")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                ToneChip(t("power.kind.${item.str("kind")}"), Tone.Inactive)
                                Mono(item.str("name"))
                                Text(
                                    t("power.state.$state") + item.str("error").let { if (it.isNotEmpty()) " – $it" else "" },
                                    style = TitanType.rowSub,
                                    color = when (state) { "stopped" -> Titan.colors.text; "failed" -> Titan.colors.errorText; else -> Titan.colors.muted },
                                )
                            }
                        }
                    } else if (preparing != null) Muted(t("power.preparingNothing"))
                    if (preparing?.str("phase") == "waiting") Banner(t("power.stuck", "names" to stuck.joinToString(", ") { it.str("name") }), tone = Tone.Warn)
                    if (preparing?.str("phase") == "failed") Banner(t("power.goingFailed", "error" to preparing.str("error")), tone = Tone.Error)
                    if (error.isNotEmpty()) Banner(error, tone = Tone.Warn)
                    Muted(t("power.callOffHint"))
                }
                "done" -> Muted(t(if (restart) "power.restartingText" else "power.shutDownText"))
                else -> {
                    status?.get("pending")?.let { it as? JsonObject }?.let { pending ->
                        Banner(
                            t(if (pending.str("action") == "reboot") "power.pendingRestart" else "power.pendingShutdown", "user" to pending.str("by")),
                            tone = Tone.Warn, action = t("power.cancelWaiting"), onAction = { cancelWaiting() },
                        )
                    }
                    for (key in listOf("power.servicesNote", "power.workloadsNote", "power.maintenanceNote", "power.diskTestNote")) Dot(t(key), muted = false)
                    if (!restartAction) Dot(t("power.wakeOnLanNote"), muted = true)
                    Dot(t(if (restartAction) "power.restartNote" else "power.shutdownNote"), muted = true)
                    if (status?.bool("rebuilding") == true) CheckRow(afterRebuild, { afterRebuild = it }, t("power.afterRebuild"))
                    if (error.isNotEmpty()) Banner(error, tone = Tone.Warn)
                }
            }
        }
    })
}

@Composable
private fun Body(text: String) = Text(text, style = TitanType.body, color = Titan.colors.text)

@Composable
private fun Dot(text: String, muted: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = TitanType.rowSub, color = Titan.colors.muted)
        Text(text, style = TitanType.rowSub, color = if (muted) Titan.colors.muted else Titan.colors.text, modifier = Modifier.weight(1f))
    }
}
