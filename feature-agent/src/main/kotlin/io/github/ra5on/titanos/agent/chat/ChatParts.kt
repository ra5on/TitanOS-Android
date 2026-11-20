package io.github.ra5on.titanos.agent.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Die Orange des Agenten (Glyphe `agent`, Verlauf `ember`): Senden und Akzente der Agent-App. */
val AgentEmber = Color(0xFFE07A4C)

@Composable
fun problemText(p: AgentProblem): String = when (p) {
    AgentProblem.Offline -> stringResource(R.string.agent_err_offline)
    AgentProblem.NoVolume -> stringResource(R.string.agent_err_no_volume)
    AgentProblem.NotAllowed -> stringResource(R.string.agent_err_not_allowed)
    is AgentProblem.Limit -> pluralStringResource(R.plurals.agent_err_limit, maxOf(p.runs, 1), maxOf(p.runs, 1))
    AgentProblem.Busy -> stringResource(R.string.agent_err_busy)
    AgentProblem.NothingPending -> stringResource(R.string.agent_err_nothing_pending)
    AgentProblem.TooLarge -> stringResource(R.string.agent_err_too_large)
    AgentProblem.Certificate -> stringResource(R.string.agent_err_cert)
    AgentProblem.Unavailable -> stringResource(R.string.agent_err_unavailable)
    is AgentProblem.Other -> stringResource(R.string.agent_err_other, p.detail)
}

@Composable
fun levelLabel(level: String): String = when (level) {
    "off" -> stringResource(R.string.agent_level_off)
    "on" -> stringResource(R.string.agent_level_on)
    "minimal" -> stringResource(R.string.agent_level_minimal)
    "low" -> stringResource(R.string.agent_level_low)
    "medium" -> stringResource(R.string.agent_level_medium)
    "high" -> stringResource(R.string.agent_level_high)
    "xhigh" -> stringResource(R.string.agent_level_xhigh)
    "max" -> stringResource(R.string.agent_level_max)
    else -> level
}

private val STEP_RES = mapOf(
    "files" to R.string.step_files, "search" to R.string.step_search, "read" to R.string.step_read,
    "write" to R.string.step_write, "move" to R.string.step_move, "copy" to R.string.step_copy,
    "folder" to R.string.step_folder, "delete" to R.string.step_delete, "docker" to R.string.step_docker,
    "logs" to R.string.step_logs, "vms" to R.string.step_vms, "storage" to R.string.step_storage,
    "shares" to R.string.step_shares, "system" to R.string.step_system, "hardware" to R.string.step_hardware,
    "backups" to R.string.step_backups, "notifications" to R.string.step_notifications, "web" to R.string.step_web,
    "page" to R.string.step_page, "memory" to R.string.step_memory, "skill" to R.string.step_skill,
    "ask" to R.string.step_ask, "home" to R.string.step_home, "homeCall" to R.string.step_homeCall,
    "plan" to R.string.step_plan, "toolsLoad" to R.string.step_toolsLoad, "delegate" to R.string.step_delegate,
    "code" to R.string.step_code,
)

/** Der Schritt in Worten („Speicher gelesen“), sonst der Name des Werkzeugs. */
@Composable
fun stepText(e: Entry): String = when {
    e.role == "note" && e.note == "approved" -> stringResource(R.string.agent_step_approved)
    e.role == "note" && e.note == "declined" -> stringResource(R.string.agent_step_declined)
    e.role == "note" && e.note == "verify" -> stringResource(R.string.agent_step_verify)
    else -> STEP_RES[stepWord(e.tool)]?.let { stringResource(it) } ?: (e.tool ?: "").replace('_', ' ')
}

fun stepIcon(e: Entry): ImageVector = when {
    e.role == "note" && e.note == "approved" -> TitanIcons.check
    e.role == "note" && e.note == "declined" -> TitanIcons.x
    e.tool?.startsWith("calendar") == true -> TitanIcons.cal
    e.tool?.startsWith("mail") == true -> TitanIcons.mail
    e.tool?.startsWith("storage") == true || e.tool?.startsWith("hardware") == true -> TitanIcons.hdd
    else -> when (stepKind(e.tool)) {
        StepKind.Web -> TitanIcons.globe
        StepKind.Files -> TitanIcons.folder
        StepKind.Write -> TitanIcons.edit
        StepKind.Home -> TitanIcons.home
        StepKind.Switch -> AgentIcons.switch
        StepKind.Code -> AgentIcons.code
        StepKind.System -> TitanIcons.monitor
        StepKind.Thinking -> TitanIcons.sparkle
    }
}

private fun seconds(ms: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f", ms / 1000.0)

/**
 * Schritte als eine Zeile mit Zeichen, Worten und Zeit („Kalender gelesen ·
 * Speicher gelesen · 1,4 s ›“); Tippen klappt die Liste mit Einzelheiten auf.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepsLine(block: Block.Steps, key: String) {
    val c = Titan.colors
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val steps = block.steps
    if (steps.isEmpty() && block.thinking.isEmpty()) return
    val total = steps.sumOf { it.durationMs ?: 0 }
    val distinct = steps.distinctBy { stepText(it) }
    val shown = distinct.take(3)
    val more = steps.size - shown.size
    val stateText = stringResource(if (open) R.string.agent_steps_hide else R.string.agent_steps_show)
    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = stateText }.heightIn(min = 40.dp).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (steps.isEmpty()) {
                Icon(AgentIcons.brain, null, tint = c.muted, modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
                Text(stringResource(R.string.agent_thought), style = TitanType.body, color = c.muted)
            }
            shown.forEachIndexed { i, s ->
                if (i > 0) Text("·", style = TitanType.body, color = c.muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(stepIcon(s), null, tint = if (stepFailed(s)) c.dangerInk else c.muted, modifier = Modifier.size(18.dp))
                    Text(stepText(s), style = TitanType.body, color = c.muted)
                }
            }
            if (more > 0 || steps.size > 1) {
                Text("·", style = TitanType.body, color = c.muted)
                Text(
                    pluralStringResource(R.plurals.agent_steps, steps.size, steps.size),
                    style = TitanType.body,
                    color = c.muted,
                )
            }
            // Zeit und Pfeil bleiben zusammen, der Pfeil steht nie allein in einer Zeile.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (total > 0) {
                    Text("·", style = TitanType.body, color = c.muted)
                    Text(stringResource(R.string.agent_seconds, seconds(total)), style = TitanType.body, color = c.muted)
                }
                Icon(if (open) TitanIcons.down else TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in steps) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(stepIcon(s), null, tint = if (stepFailed(s)) c.dangerInk else c.muted, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stepText(s), style = TitanType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
                            val detail = stepDetail(s.args).ifEmpty { s.summary.orEmpty() }
                            if (detail.isNotEmpty()) Text(detail, style = TitanType.small, color = c.muted)
                            if (stepFailed(s)) {
                                Text(stringResource(R.string.agent_step_failed) + ": " + s.result.orEmpty().removePrefix("Error:").trim(), style = TitanType.small, color = c.dangerInk)
                            }
                        }
                        s.durationMs?.takeIf { it > 0 }?.let { Text(stringResource(R.string.agent_seconds, seconds(it)), style = TitanType.small, color = c.muted) }
                    }
                }
                if (block.thinking.isNotBlank()) {
                    Text(stringResource(R.string.agent_thought), style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                    Text(block.thinking.trim(), style = TitanType.small, color = c.muted)
                }
            }
        }
    }
}

/** Nachricht der Person: Blase rechts. */
@Composable
fun UserBubble(text: String) {
    val c = Titan.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = AnswerStyle,
            color = c.text,
            modifier = Modifier.padding(start = 48.dp).clip(RoundedCornerShape(22.dp)).background(c.chip)
                .border(1.dp, c.border, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

/** Kleiner runder Symbolknopf unter einer Antwort (Kopieren, Neu erzeugen). */
@Composable
fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Titan.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(19.dp))
    }
}

/** Eine Notiz des Laufs (angehalten, unterbrochen, Fehler …) mit Zeichen und Worten. */
@Composable
fun NoteLine(e: Entry) {
    val c = Titan.colors
    val text = when (e.note) {
        "stopped" -> stringResource(R.string.agent_note_stopped)
        "interrupted" -> stringResource(R.string.agent_note_interrupted)
        "error" -> stringResource(R.string.agent_note_error, e.content ?: e.summary.orEmpty())
        "memory" -> stringResource(R.string.agent_note_memory, e.content ?: e.summary.orEmpty())
        "limit" -> stringResource(R.string.agent_note_limit)
        "timelimit" -> stringResource(R.string.agent_note_timelimit)
        "summary" -> stringResource(R.string.agent_note_summary)
        else -> stringResource(R.string.agent_note_other, e.content ?: e.summary ?: e.note.orEmpty())
    }
    val warn = e.note == "error" || e.note == "interrupted"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(if (warn) TitanIcons.warn else TitanIcons.info, null, tint = if (warn) c.warnInk else c.muted, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = TitanType.body, color = if (warn) c.warnInk else c.muted)
    }
}

private fun JsonObject.lines(): List<Pair<String, String>> = entries
    .filter { it.key != "content" && it.key != "sheets" && it.key != "code" }
    .map { (k, v) -> k to ((v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.toString()) }

private fun preview(p: Pending): String {
    val o = p.args as? JsonObject ?: return ""
    (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    return (o["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

@Composable
private fun askTitle(p: Pending): String {
    val plain = approvalTitle(p)
    val res = plain?.let { askCardRes(it.card) }
    val what = if (res != null) stringResource(res, plain.name) else p.summary.ifEmpty { p.tool.replace('_', ' ') }
    return stringResource(R.string.web_ask_title, what)
}

private fun askCardRes(card: String): Int? = when (card) {
    "vm.start" -> R.string.ask_card_vm_start
    "vm.shutdown" -> R.string.ask_card_vm_shutdown
    "vm.reboot" -> R.string.ask_card_vm_reboot
    "vm.poweroff" -> R.string.ask_card_vm_poweroff
    "vm.update" -> R.string.ask_card_vm_update
    "vm.remove" -> R.string.ask_card_vm_remove
    "vm.purge" -> R.string.ask_card_vm_purge
    "container.start" -> R.string.ask_card_container_start
    "container.stop" -> R.string.ask_card_container_stop
    "container.restart" -> R.string.ask_card_container_restart
    "lxc.start" -> R.string.ask_card_lxc_start
    "lxc.stop" -> R.string.ask_card_lxc_stop
    "lxc.restart" -> R.string.ask_card_lxc_restart
    "lxc.kill" -> R.string.ask_card_lxc_kill
    "app.update" -> R.string.ask_card_app_update
    "app.remove" -> R.string.ask_card_app_remove
    "app.purge" -> R.string.ask_card_app_purge
    "apps.updateAll" -> R.string.ask_card_apps_updateAll
    "file.write" -> R.string.ask_card_file_write
    "file.replace" -> R.string.ask_card_file_replace
    "file.edit" -> R.string.ask_card_file_edit
    "file.rename" -> R.string.ask_card_file_rename
    "file.delete" -> R.string.ask_card_file_delete
    "file.trash" -> R.string.ask_card_file_trash
    "files.delete" -> R.string.ask_card_files_delete
    "files.move" -> R.string.ask_card_files_move
    "files.restore" -> R.string.ask_card_files_restore
    "folder.create" -> R.string.ask_card_folder_create
    "share.create" -> R.string.ask_card_share_create
    "share.delete" -> R.string.ask_card_share_delete
    "volume.check" -> R.string.ask_card_volume_check
    "volume.stopCheck" -> R.string.ask_card_volume_stopCheck
    "volume.delete" -> R.string.ask_card_volume_delete
    "backup.run" -> R.string.ask_card_backup_run
    "backup.stop" -> R.string.ask_card_backup_stop
    "power.reboot" -> R.string.ask_card_power_reboot
    "power.poweroff" -> R.string.ask_card_power_poweroff
    "update.install" -> R.string.ask_card_update_install
    "update.rollback" -> R.string.ask_card_update_rollback
    "account.delete" -> R.string.ask_card_account_delete
    "plugin.enable" -> R.string.ask_card_plugin_enable
    "plugin.disable" -> R.string.ask_card_plugin_disable
    "plugin.remove" -> R.string.ask_card_plugin_remove
    "plugin.update" -> R.string.ask_card_plugin_update
    else -> null
}

/** Wann angefragt, kurz („14:05“ heute, sonst Datum und Zeit). */
private fun askedAt(rfc3339: String, locale: Locale = Locale.getDefault()): String {
    val at = Format.instant(rfc3339) ?: return ""
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(at.atZone(ZoneId.systemDefault()))
}

/**
 * Ein Schritt, der auf die Person wartet (ApprovalCard im Web): Ring mit
 * „Wartet auf deine Freigabe“, Titel in Worten, Stufe ausgeschrieben (nie nur
 * Farbe), Zeit, „Abbrechen“ und „Ausführen“, Details mit Werkzeug und Argumenten.
 */
@Composable
fun ApprovalCard(p: Pending, deciding: Boolean, readOnly: Boolean, onDecide: (Boolean) -> Unit) {
    val c = Titan.colors
    var details by rememberSaveable(p.id) { mutableStateOf(preview(p).isNotEmpty()) }
    val danger = p.level != "normal"
    val level = when {
        p.kind == "read" && p.level == "normal" -> stringResource(R.string.web_levelRead)
        p.level == "danger" -> stringResource(R.string.web_levelDanger)
        p.level == "destroy" -> stringResource(R.string.web_levelDestroy)
        else -> stringResource(R.string.web_levelChange)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AgentIcons.ring, null, tint = AgentEmber, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.web_waitsForYou), style = TitanType.body, color = c.muted)
        }
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(if (danger) c.warnBg else c.surface)
                .border(1.dp, if (danger) c.warnBorder else c.border, shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (danger) TitanIcons.warn else TitanIcons.edit, null, tint = if (danger) c.warnInk else c.text, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(askTitle(p), style = TitanType.rowTitle, color = c.text)
                    val at = askedAt(p.at)
                    Text(
                        stringResource(R.string.web_ask_level, level) + if (at.isNotEmpty()) " · " + stringResource(R.string.web_ask_at, at) else "",
                        style = TitanType.rowSub,
                        color = c.muted,
                    )
                    when (p.why) {
                        "pim" -> Text(stringResource(R.string.web_ask_whyPim), style = TitanType.rowSub, color = c.muted)
                        "outside" -> Text(stringResource(R.string.web_ask_whyOutside), style = TitanType.rowSub, color = c.muted)
                    }
                }
            }
            if (!readOnly) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    io.github.ra5on.titanos.ui.components.TitanButton(
                        stringResource(R.string.web_decline), { onDecide(false) }, Modifier.weight(1f), enabled = !deciding,
                    )
                    io.github.ra5on.titanos.ui.components.TitanButton(
                        stringResource(if (p.tool == "mail_delete") R.string.web_ask_delete else R.string.web_approve),
                        { onDecide(true) },
                        Modifier.weight(1f),
                        if (p.level == "destroy") io.github.ra5on.titanos.ui.components.ButtonKind.Danger else io.github.ra5on.titanos.ui.components.ButtonKind.Primary,
                        enabled = !deciding,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { details = !details }.heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (details) TitanIcons.down else TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.web_ask_details), style = TitanType.small, color = c.muted)
            }
            if (details) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.web_ask_tool) + ": " + p.tool + if (p.summary.isNotEmpty() && p.tool != "code_run") " — " + p.summary else "", style = TitanType.small, color = c.text)
                    (p.args as? JsonObject)?.lines()?.forEach { (k, v) -> Text("$k: $v", style = TitanType.small, color = c.muted) }
                    val shown = preview(p)
                    if (shown.isNotEmpty()) {
                        Text(
                            shown,
                            style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono),
                            color = c.text,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Antworten mit Wahlknöpfen (ask_user): Tippen schickt die Wahl als nächste Nachricht. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Choices(choices: List<String>, enabled: Boolean, onChoice: (String) -> Unit) {
    val c = Titan.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (ch in choices) {
            Text(
                ch,
                style = TitanType.body,
                color = if (enabled) c.text else c.muted,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(c.chip).border(1.dp, c.border, RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled, role = Role.Button) { onChoice(ch) }
                    .heightIn(min = 40.dp).widthIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
