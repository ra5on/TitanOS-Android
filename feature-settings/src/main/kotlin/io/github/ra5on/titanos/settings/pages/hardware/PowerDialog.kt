package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Rückfrage vor Neustart oder Ausschalten und der Wartebildschirm danach
 * (PowerDialog.svelte): erst bestätigen, dann fährt titand Gäste und
 * Skripte herunter (Liste mit Zustand), dann geht das NAS. Beides verlangt
 * den Fingerabdruck ([GuardAction.Power]). Statt die Seite neu zu laden
 * (Web) schließt der Dialog, sobald das NAS nach einem Neustart wieder antwortet.
 */
@Composable
internal fun BoxScope.PowerDialog(restartAction: Boolean, onClose: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // confirm, working, preparing, done, waiting
    var phase by remember { mutableStateOf("confirm") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var afterRebuild by remember { mutableStateOf(true) }
    val preparing = status?.get("preparing") as? JsonObject
    val restart = if (preparing != null) preparing.str("action") == "reboot" else restartAction

    fun finish() {
        phase = "done"
    }

    suspend fun load() {
        val s = try {
            env.api.obj("system/power")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Während das NAS herunterfährt, kommen keine Antworten mehr: es ist unterwegs.
            if (phase == "preparing") finish()
            return
        }
        status = s
        val p = s["preparing"] as? JsonObject
        if (p != null && phase == "confirm") phase = "preparing"
        if (phase != "preparing") return
        if (p?.str("phase") == "going" || (p == null && !busy)) finish()
    }

    LaunchedEffect(Unit) { load() }
    // Während der Vorbereitung folgt die Liste den Gästen.
    LaunchedEffect(phase) {
        if (phase != "preparing") return@LaunchedEffect
        while (true) {
            delay(2000)
            load()
        }
    }
    // Nach dem Neustart: warten, bis das NAS erst weg und dann wieder da ist (oder eine halbe Minute still war).
    LaunchedEffect(phase, restart) {
        if (phase != "done" || !restart) return@LaunchedEffect
        val started = System.currentTimeMillis()
        var wasDown = false
        while (true) {
            delay(3000)
            val ok = try {
                env.api.get("health"); true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                wasDown = true; false
            }
            if (ok && (wasDown || System.currentTimeMillis() - started > 30_000)) {
                onClose()
                return@LaunchedEffect
            }
        }
    }

    fun confirm() {
        Guard.require(context, GuardAction.Power) {
            error = ""
            phase = "working"
            val wait = status.bool("rebuilding") && afterRebuild
            scope.launch {
                try {
                    val res = env.api.post("system/power", jobj("action" to if (restartAction) "reboot" else "poweroff", "afterRebuild" to wait, "wait" to true))
                    if (wait && (res as? JsonObject).bool("pending")) {
                        phase = "waiting"
                        return@launch
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e)
                    phase = "confirm"
                    return@launch
                }
                busy = true
                phase = "preparing"
                // Die erste Liste kommt einen Moment nach der Anfrage.
                delay(600)
                busy = false
                load()
            }
        }
    }

    fun anyway() {
        Guard.require(context, GuardAction.Power) {
            error = ""
            scope.launch {
                try {
                    env.api.post("system/power/now", JsonObject(emptyMap()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e)
                }
            }
        }
    }

    fun cancelWaiting() {
        error = ""
        scope.launch {
            try {
                status = env.api.delete("system/power") as? JsonObject ?: status
                if (phase == "preparing") onClose()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }

    val title = t(
        when {
            phase == "preparing" -> if (restart) "power.preparingRestart" else "power.preparingShutdown"
            phase != "done" -> if (restart) "power.restartTitle" else "power.shutdownTitle"
            else -> if (restart) "power.restarting" else "power.shuttingDown"
        },
    )
    val locked = phase == "working" || phase == "done" || phase == "preparing"
    HwDialog(
        title,
        onDismiss = onClose,
        locked = locked,
        buttons = when (phase) {
            "waiting" -> ({ DialogButton(t("common.close"), onClose) })
            "preparing" -> ({
                DialogButton(t(if (restart) "power.anywayRestart" else "power.anywayShutdown"), { anyway() }, danger = true, enabled = preparing?.str("phase") != "going")
                DialogButton(t("power.callOff"), { cancelWaiting() })
            })
            "done" -> null
            else -> ({
                DialogButton(t(if (restartAction) "power.restartAction" else "power.shutdownAction"), { confirm() }, danger = true, enabled = phase != "working")
                DialogButton(t("common.cancel"), onClose, enabled = phase != "working")
            })
        },
    ) {
        when (phase) {
            "waiting" -> Plain(t(if (restartAction) "power.waitingRestart" else "power.waitingShutdown"))
            "preparing" -> {
                Muted(t(if (restart) "power.preparingTextRestart" else "power.preparingTextShutdown"))
                val items = preparing.a("items").objects()
                if (preparing != null && items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (item in items) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Badge(t("power.kind.${item.str("kind")}"))
                                Mono(item.str("name"), muted = false)
                                val st = item.str("state")
                                StatusChip(
                                    t("power.state.$st"),
                                    when (st) {
                                        "stopped" -> Tone.Ok
                                        "failed" -> Tone.Error
                                        else -> Tone.Inactive
                                    },
                                )
                                if (item.str("error").isNotEmpty()) Muted(item.str("error"), small = true)
                            }
                        }
                    }
                } else if (preparing != null) {
                    Muted(t("power.preparingNothing"))
                }
                if (preparing?.str("phase") == "waiting") {
                    val stuck = items.filter { it.str("kind") != "script" && it.str("state") != "stopped" }
                    Banner(t("power.stuck", "names" to stuck.joinToString(", ") { it.str("name") }), tone = Tone.Warn)
                }
                if (preparing?.str("phase") == "failed") Banner(t("power.goingFailed", "error" to preparing.str("error")), tone = Tone.Error)
                if (error.isNotEmpty()) Banner(error, tone = Tone.Warn)
                Muted(t("power.callOffHint"), small = true)
            }
            "done" -> Muted(t(if (restart) "power.restartingText" else "power.shutDownText"))
            else -> {
                val pending = status?.get("pending") as? JsonObject
                if (pending != null) {
                    Banner(
                        t(if (pending.str("action") == "reboot") "power.pendingRestart" else "power.pendingShutdown", "user" to pending.str("by")),
                        tone = Tone.Warn,
                        action = t("power.cancelWaiting"),
                        onAction = { cancelWaiting() },
                    )
                }
                Bullet(t("power.servicesNote"))
                Bullet(t("power.workloadsNote"))
                Bullet(t("power.maintenanceNote"))
                Bullet(t("power.diskTestNote"))
                if (!restartAction) Bullet(t("power.wakeOnLanNote"), muted = true)
                Bullet(t(if (restartAction) "power.restartNote" else "power.shutdownNote"), muted = true)
                if (status.bool("rebuilding")) CheckRow(afterRebuild, { afterRebuild = it }, t("power.afterRebuild"))
                if (error.isNotEmpty()) Banner(error, tone = Tone.Warn)
            }
        }
    }
}

/** Ein Knopf „Jetzt neu starten“, der den [PowerDialog] öffnet. */
@Composable
internal fun RestartButton(text: String) {
    val ov = io.github.ra5on.titanos.ui.components.LocalOverlays.current
    SmallButton(text, { ov.show { close -> PowerDialog(restartAction = true, onClose = close) } })
}

/**
 * Neustart oder Ausschalten des NAS von außerhalb der Systemsteuerung (Hauptmenü
 * des Arbeitsplatzes wie im Web): derselbe Dialog mit Rückfrage und Fingerabdruck.
 */
@Composable
fun BoxScope.NasPowerDialog(env: io.github.ra5on.titanos.settings.common.SettingsEnv, restart: Boolean, onClose: () -> Unit) {
    val tr = io.github.ra5on.titanos.settings.shell.rememberSettingsTr()
    androidx.compose.runtime.CompositionLocalProvider(io.github.ra5on.titanos.ui.i18n.LocalTr provides tr, LocalEnv provides env) {
        PowerDialog(restartAction = restart, onClose = onClose)
    }
}
