package io.github.ra5on.titanos.agent.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.app.AppHooks
import io.github.ra5on.titanos.agent.app.openTitanOS
import io.github.ra5on.titanos.agent.app.titanOSInstalled
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.levelText
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.Look
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.hexColor
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.strs
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.figure.BusyFigure
import io.github.ra5on.titanos.agent.figure.FigureClock
import io.github.ra5on.titanos.agent.pages.initiativeDefaults
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.AgentWarm
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Lead
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PagePadding
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.Range
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Segments
import io.github.ra5on.titanos.agent.ui.Select
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.ui.rememberDoc
import io.github.ra5on.titanos.agent.voice.Speech
import io.github.ra5on.titanos.agent.features.FeaturesPanel
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val PARTS = listOf("assistant", "models", "speech", "personality", "initiative", "features", "photos", "pim", "push", "look", "gestures", "about")

/** Der Titel eines Teils der Einstellungen (titles im Web). */
fun partTitle(tr: io.github.ra5on.titanos.agent.core.Tr, part: String): String = when (part) {
    "models" -> tr.t("agent.set.models")
    "speech" -> tr.t("agent.set.speech")
    "personality" -> tr.t("agent.init.personality")
    "initiative" -> tr.t("agent.set.initiative")
    "features" -> tr.t("agent.f.title")
    "photos" -> tr.t("agent.photos.title")
    "pim" -> tr.t("agentpim.mine.title")
    "push" -> tr.t("agent.set.push")
    "look" -> tr.t("agent.set.lookTitle")
    "gestures" -> tr.t("agent.set.gestures")
    "assistant" -> if (tr.de) "Assistent, Notizen und Auto" else "Assistant, notes and car"
    else -> tr.t("agent.set.title")
}

/**
 * Die Einstellungen des Agenten (SettingsPage.svelte): Modell und Sprache,
 * Verhalten (Persönlichkeit, Eigeninitiative, Funktionen, Fotos, Mail/Termine/
 * Kontakte), dieses Gerät (Mitteilungen, Darstellung samt Figur, Gesten,
 * Beim Öffnen), die Verbindung am NAS, „Alle Chats löschen“ und die App.
 */
@Composable
fun SettingsPage(
    model: AgentModel,
    part: String,
    onPart: (String) -> Unit,
    hooks: AppHooks,
    speech: Speech,
    clock: FigureClock,
    onDeleteAll: () -> Unit,
    onFeature: (String) -> Unit,
    onKnow: () -> Unit,
) {
    val tr = tr()
    val p = if (part in PARTS) part else ""
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (p) {
            "" -> overview(model, hooks, onPart, onDeleteAll)
            "models" -> item { Models(model) }
            "speech" -> item { SpeechPart(model, speech) }
            "initiative" -> item { Initiative(model, mind = false, onKnow) }
            "personality" -> item { Initiative(model, mind = true, onKnow) }
            "features" -> item { FeaturesPanel(model, onFeature) }
            "photos" -> item { Photos(model) }
            "pim" -> item { MyActionRights(model) }
            "push" -> item { Push(model) }
            "look" -> item { LookPart(model, clock) }
            "gestures" -> item { Gestures(model) }
            "about" -> item { About(model, hooks) }
            "assistant" -> item { Assistant() }
        }
    }
}

private fun LazyListScope.overview(model: AgentModel, hooks: AppHooks, onPart: (String) -> Unit, onDeleteAll: () -> Unit) {
    item { Lead(trOf(model).t("agent.set.lead")) }
    item {
        val tr = tr()
        SGroup(tr.t("agent.set.groupModel")) {
            SRow(tr.t("agent.set.models"), desc = tr.t("agent.set.modelsDesc"), icon = "chip", onClick = { onPart("models") })
            SRow(tr.t("agent.set.speech"), desc = tr.t("agent.set.speechDesc"), icon = "volume", onClick = { onPart("speech") })
        }
    }
    item {
        val tr = tr()
        SGroup(tr.t("agent.set.groupBehaviour")) {
            SRow(tr.t("agent.init.personality"), desc = tr.t("agent.set.personalityDesc"), icon = "star", onClick = { onPart("personality") })
            SRow(tr.t("agent.set.initiative"), desc = tr.t("agent.set.initiativeDesc"), icon = "activity", onClick = { onPart("initiative") })
            SRow(tr.t("agent.f.title"), desc = tr.t("agent.f.sub"), icon = "grid", onClick = { onPart("features") })
            SRow(tr.t("agent.photos.title"), desc = tr.t("agent.photos.desc"), icon = "image", onClick = { onPart("photos") })
            SRow(tr.t("agentpim.mine.title"), desc = tr.t("agentpim.mine.desc"), icon = "mail", onClick = { onPart("pim") })
        }
    }
    item {
        val tr = tr()
        val look = model.look
        SGroup(tr.t("agent.set.groupDevice")) {
            SRow(tr.t("agent.set.push"), desc = tr.t("agent.set.pushSub"), icon = "bell", onClick = { onPart("push") })
            SRow(tr.t("agent.set.lookTitle"), desc = tr.t("agent.set.lookDesc"), icon = "eye", onClick = { onPart("look") })
            SRow(tr.t("agent.set.gestures"), desc = tr.t("agent.set.gesturesDesc"), icon = "move", onClick = { onPart("gestures") })
            SRow(partTitle(tr, "assistant"), desc = if (tr.de) "Digitaler Assistent, Bildschirminhalt, Notizen-App, Android Auto" else "Digital assistant, screen content, notes app, Android Auto", icon = "mic", onClick = { onPart("assistant") })
            SRow(tr.t("agent.set.startLabel"), desc = tr.t("agent.set.startDesc"), icon = "home", control = {
                Segments(listOf("new", "last", "overview").map { it to tr.t("agent.set.start.$it") }, look.start, { model.changeLook(look.copy(start = it)) }, fill = true)
            })
        }
    }
    if (model.status?.admin == true) item {
        val tr = tr()
        val context = LocalContext.current
        SGroup(tr.t("agent.set.groupNas")) {
            SRow(tr.t("agent.set.nas"), desc = tr.t("agent.set.nasDesc"), icon = "external", onClick = if (titanOSInstalled(context)) ({ openTitanOS(context, "/settings/agent") }) else null)
        }
    }
    item {
        val tr = tr()
        val own = model.chats.count { it.author == null }
        SGroup(tr.t("agent.set.groupData")) {
            SRow(tr.t("agent.deleteAll"), desc = if (own > 0) tr.t("agent.deleteAllSub", "count" to own) else tr.t("agent.set.noChats"), icon = "trash", control = {
                PillButton(tr.t("agent.set.deleteAsk"), onDeleteAll, kind = Btn.Danger, enabled = own > 0, small = true)
            })
        }
    }
    item {
        val tr = tr()
        SGroup(androidApp(tr)) {
            SRow(androidAbout(tr), desc = "${hooks.versionName} · ${hooks.hostname.ifEmpty { "NAS" }}", icon = "info", onClick = { onPart("about") })
        }
    }
}

@Composable private fun trOf(@Suppress("UNUSED_PARAMETER") m: AgentModel) = tr()

/** „Über die App“ ist eigen (die Web-Seite hat keinen Teil dafür); Worte aus den Texten der App. */
private fun androidApp(tr: io.github.ra5on.titanos.agent.core.Tr) = if (tr.de) "Agent-App" else "Agent app"
private fun androidAbout(tr: io.github.ra5on.titanos.agent.core.Tr) = if (tr.de) "Über die App und Kopplung" else "About the app and pairing"

@Composable
private fun Row2(title: String, desc: String? = null, control: @Composable () -> Unit) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        if (!desc.isNullOrEmpty()) Text(desc, style = AgentText.sm, color = c.muted)
        control()
    }
}

@Composable
private fun SwitchRow(title: String, desc: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Titan.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) c.text else c.muted)
            if (!desc.isNullOrEmpty()) Text(desc, style = AgentText.sm, color = c.muted)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** Eine Gruppe von Zeilen in einer Karte (.grp im Web). */
@Composable
private fun Grp(cap: String? = null, content: @Composable () -> Unit) {
    SGroup(cap) {
        Column(Modifier.fillMaxWidth().clip(io.github.ra5on.titanos.agent.ui.SRowShape).background(io.github.ra5on.titanos.agent.ui.AgentColors.srowBg).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Models(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val context = LocalContext.current
    val s = model.status ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Grp {
            Row2(tr.t("agent.set.chatModel"), if (!s.modelLabel.isNullOrEmpty()) "${s.modelLabel} (${s.model})" else s.model + if (s.think.isNotEmpty()) " · " + levelText(tr, s.think) else "") {}
            Row2(tr.t("agent.set.visionModel"), s.vision?.ifEmpty { null } ?: tr.t("agent.set.visionSame")) {}
        }
        Text(tr.t("agent.set.modelsHint"), style = AgentText.sm, color = c.muted)
        SGroup(tr.t("agent.model")) {
            for (m in model.usable) SRow(m.shown, desc = listOfNotNull(io.github.ra5on.titanos.ui.Format.bytes(m.size), m.parameters, if ("vision" in m.capabilities) tr.t("agent.capVision") else null, if ("thinking" in m.capabilities) tr.t("agent.capThinking") else null).joinToString(" · "), icon = "chip",
                badge = if (m.name == s.model) ({ Tag(tr.t("agent.modelDefault").let { if (it == "agent.modelDefault") "✓" else it }, tone = "info") }) else null)
        }
        if (s.admin && titanOSInstalled(context)) PillButton(tr.t("agent.set.modelsAdmin"), { openTitanOS(context, "/settings/agent?tab=connection") }, icon = "gear")
    }
}

@Composable
private fun SpeechPart(model: AgentModel, speech: Speech) {
    val tr = tr()
    val look = model.look
    val s = model.status
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Grp {
            SwitchRow(tr.t("agent.set.readAutoLabel"), tr.t("agent.set.readAutoHint"), look.read) { model.changeLook(look.copy(read = it)) }
            Row2(tr.t("agent.set.rate"), String.format(if (tr.de) java.util.Locale.GERMAN else java.util.Locale.ENGLISH, "%.1f×", look.rate)) {
                Range(look.rate.toFloat(), 0.5f..2f, { model.changeLook(look.copy(rate = Math.round(it * 10) / 10.0)) }, String.format("%.1f×", look.rate), steps = 14)
            }
            PillButton(tr.t("agent.readAloud"), { speech.speak("preview", tr.t("agent.busy.done") + ". " + tr.t("agent.set.readAutoHint")) }, icon = "volume", small = true)
        }
        Grp(tr.t("agent.set.voiceIn")) {
            Row2(tr.t("agent.set.whisper"), if (model.speechHear) tr.t("agent.set.whisperNas") else if (s?.voice == true) tr.t("agent.set.whisperOn") else tr.t("agent.set.whisperOff")) {
                if (s?.voice != true && !model.speechHear && s?.admin == true && titanOSInstalled(context)) PillButton(tr.t("agent.set.setup"), { openTitanOS(context, "/settings/speech") }, kind = Btn.Primary, small = true)
            }
            Row2(tr.t("agent.set.mic")) {
                Segments(listOf("tap", "hold").map { it to tr.t("agent.set.mic.$it") }, look.mic, { model.changeLook(look.copy(mic = it)) }, fill = true)
            }
        }
    }
}

/** Eigeninitiative oder Persönlichkeit (InitiativeSettings.svelte). */
@Composable
private fun Initiative(model: AgentModel, mind: Boolean, onKnow: () -> Unit) {
    val tr = tr()
    val c = Titan.colors
    val ini = rememberDoc(model, "agent/initiative", initiativeDefaults())
    val v = ini.obj
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ini.error?.let { Notice(it, tone = "error") }
        if (!mind) {
            Grp(tr.t("agent.init.how")) {
                Segments(listOf("quiet", "hints", "suggest", "act").map { it to tr.t("agent.init.level.$it") }, v.str("level", "suggest"), { ini.set("level" to it) }, fill = false)
            }
            Grp(tr.t("agent.init.night")) {
                SwitchRow(tr.t("agent.init.reflect"), tr.t("agent.init.reflectHint"), v.bool("reflect", true)) { ini.set("reflect" to it) }
                Row2(tr.t("agent.init.at")) { io.github.ra5on.titanos.agent.ui.TimeField(v.str("reflectAt", "03:30"), { ini.set("reflectAt" to it) }, tr.t("agent.init.at"), enabled = v.bool("reflect", true), width = 140.dp) }
                Row2(tr.t("agent.init.take"), tr.t("agent.init.takeHint")) {
                    Segments(listOf(true to tr.t("agent.init.confirm"), false to tr.t("agent.init.direct")), v.bool("confirm", true), { ini.set("confirm" to it) }, fill = true)
                }
                SwitchRow(tr.t("agent.init.morningNote"), null, v.bool("morningNote", true), enabled = v.bool("reflect", true)) { ini.set("morningNote" to it) }
            }
            Grp(tr.t("agent.init.watch")) {
                SwitchRow(tr.t("agent.init.watchLabel"), tr.t("agent.init.watchHint"), v.bool("watch", true)) { ini.set("watch" to it) }
                SwitchRow(tr.t("agent.init.forecast"), tr.t("agent.init.forecastHint"), v.bool("forecast", true)) { ini.set("forecast" to it) }
                val events = v.strs("events")
                for (ev in listOf("usb", "update")) SwitchRow(tr.t("agent.init.event.$ev"), null, ev in events) { on -> ini.set("events" to if (on) (events + ev).distinct() else events - ev) }
            }
            Grp(tr.t("agent.init.disks")) {
                Row2(tr.t("agent.init.background"), tr.t("agent.init.backgroundHint")) {}
                Row2(tr.t("agent.init.wake"), tr.t("agent.init.wakeHint")) {
                    Segments(listOf("wake", "ask", "never").map { it to tr.t("agent.init.wake.$it") }, v.str("wake", "wake"), { ini.set("wake" to it) }, fill = true)
                }
            }
            Grp(tr.t("agent.init.limits")) {
                Row2(tr.t("agent.init.quiet"), tr.t("agent.init.quietHint")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        io.github.ra5on.titanos.agent.ui.TimeField(v.str("quietFrom", "22:00"), { ini.set("quietFrom" to it) }, tr.t("agent.init.quietFrom"), width = 130.dp)
                        Text("–", style = AgentText.md, color = c.muted)
                        io.github.ra5on.titanos.agent.ui.TimeField(v.str("quietTo", "07:00"), { ini.set("quietTo" to it) }, tr.t("agent.init.quietTo"), width = 130.dp)
                    }
                }
                val gpu = v.int("gpuMinutes", 30)
                Row2(tr.t("agent.init.gpu"), tr.t("agent.init.gpuHint", "min" to gpu)) {
                    Range(gpu.toFloat(), 5f..120f, { ini.set("gpuMinutes" to (Math.round(it / 5) * 5)) }, "$gpu min", steps = 22)
                }
                SwitchRow(tr.t("agent.init.weekly"), tr.t("agent.init.weeklyHint"), v.bool("weekly", true)) { ini.set("weekly" to it) }
            }
        } else {
            Grp(tr.t("agent.init.skillsAndInbox")) {
                SwitchRow(tr.t("agent.init.learn"), tr.t("agent.init.learnHint"), v.bool("learnSkills", true)) { ini.set("learnSkills" to it) }
                Row2(tr.t("agent.init.know"), tr.t("agent.init.knowHint")) { PillButton(tr.t("agent.init.knowStart"), onKnow, small = true) }
            }
            Grp(tr.t("agent.init.personality")) {
                Row2(tr.t("agent.init.length")) { Segments(listOf("short", "normal", "long").map { it to tr.t("agent.init.length.$it") }, v.str("length", "normal"), { ini.set("length" to it) }, fill = true) }
                Row2(tr.t("agent.init.tone")) { Segments(listOf("casual", "plain").map { it to tr.t("agent.init.tone.$it") }, v.str("tone", "casual"), { ini.set("tone" to it) }, fill = true) }
                Row2(tr.t("agent.init.name"), tr.t("agent.init.nameHint")) { Field(v.str("name"), { ini.set("name" to it.take(24)) }, label = tr.t("agent.init.name"), placeholder = "Titan") }
                Row2(tr.t("agent.init.icon")) { Field(v.str("icon"), { ini.set("icon" to it.take(4)) }, Modifier.width(110.dp), label = tr.t("agent.init.icon"), placeholder = "✨") }
            }
        }
    }
}

/** Fotos über Immich (PhotosSettings.svelte). Der Schlüssel bleibt auf dem NAS; die App zeigt ihn nie. */
@Composable
private fun Photos(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var view by remember { mutableStateOf<JsonObject?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) { scope.launch { busy = true; error = null; saved = null; try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } finally { busy = false } } }
    LaunchedEffect(Unit) { try { view = model.api.getJson("agent/photos").obj() } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = problemText(tr, problemOf(e)) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr.t("agent.photos.intro"), style = AgentText.sm, color = c.muted)
        val v = view
        when {
            failed != null -> Notice(failed!!, tone = "warn")
            v == null -> Text(tr.t("common.loading"), style = AgentText.md, color = c.muted)
            else -> {
                Grp {
                    Row2(tr.t("agent.photos.immich"), if (v.bool("immich")) tr.t("agent.photos.immichOn", "port" to v.int("port")) else tr.t("agent.photos.immichOff")) {
                        if (!v.bool("immich") && titanOSInstalled(context)) PillButton(tr.t("agent.photos.install"), { openTitanOS(context, "/apps/install/titanos/immich") }, small = true)
                    }
                    if (v.bool("keySet")) Row2(tr.t("agent.photos.connected"), tr.t("agent.photos.connectedAs", "name" to v.str("userName").ifEmpty { "–" })) {}
                    if (v.bool("immich")) Row2(if (v.bool("keySet")) tr.t("agent.photos.keyNew") else tr.t("agent.photos.key"), tr.t("agent.photos.keyHow")) {
                        Field(key, { key = it.take(200) }, password = true, label = tr.t("agent.photos.key"))
                        Buttons {
                            PillButton(tr.t("agent.photos.save"), { act { view = model.api.sendJson("PUT", "agent/photos", jsonOf("key" to key.trim())).obj(); key = ""; saved = tr.t("agent.photos.saved", "name" to view?.str("userName").orEmpty()) } }, kind = Btn.Primary, enabled = !busy && key.isNotBlank())
                            if (v.bool("keySet")) {
                                PillButton(tr.t("agent.photos.check"), { act { view = model.api.sendJson("POST", "agent/photos/check").obj(); saved = tr.t("agent.photos.checked", "name" to view?.str("userName").orEmpty()) } }, enabled = !busy)
                                PillButton(tr.t("agent.photos.remove"), { act { view = model.api.sendJson("DELETE", "agent/photos").obj(); saved = tr.t("agent.photos.removed") } }, kind = Btn.Danger, enabled = !busy)
                            }
                        }
                    }
                }
                saved?.let { Notice(it, tone = "ok") }
                error?.let { Notice(it, tone = "warn") }
                val missing = v.strs("missing")
                if (missing.isNotEmpty()) Notice(tr.t("agent.photos.missing", "list" to missing.joinToString(", ")), tone = "warn")
                if (v["smart"]?.let { (it as? JsonPrimitive)?.content == "false" } == true) Notice(tr.t("agent.photos.noSmart"), tone = "warn")
                Text(tr.t("agent.photos.privacy"), style = AgentText.sm, color = c.muted)
            }
        }
    }
}

private val ACTION_GROUPS = listOf(
    "mail" to listOf("mail.read", "mail.draft", "mail.send", "mail.move"),
    "event" to listOf("event.create", "event.update", "event.delete"),
    "contact" to listOf("contact.create", "contact.update", "contact.delete"),
    "task" to listOf("task.create", "task.update", "task.complete", "task.delete"),
)
private val RANK = mapOf("allow" to 0, "ask" to 1, "suggest" to 2, "deny" to 3)
private fun actionValues(key: String) = if (key == "mail.read") listOf("allow", "deny") else if (key.endsWith(".delete")) listOf("ask") else listOf("suggest", "ask", "allow")
/** Was ein Konto für sich wählen darf: „wie vom NAS“ und alles mindestens so Strenge (ownChoices). */
fun ownChoices(key: String, nas: String): List<String> = if (nas == "deny") listOf("") else listOf("") + actionValues(key).filter { (RANK[it] ?: 3) > (RANK[nas] ?: 3) }

/** Eigene Rechte für Mail, Termine, Kontakte, Erinnerungen (MyActionRights.svelte). */
@Composable
private fun MyActionRights(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { view = model.api.getJson("agent/rights/me").obj() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } }
    fun mode(m: String) = tr.t("agentpim.mode.$m")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr.t("agentpim.mine.intro"), style = AgentText.sm, color = c.muted)
        Notice(tr.t("agentpim.cloud.settings"), tone = "warn")
        error?.let { Notice(it, tone = "error") }
        val v = view
        if (v == null && error == null) Text(tr.t("common.loading"), style = AgentText.md, color = c.muted)
        if (v != null) for ((g, keys) in ACTION_GROUPS) Grp(tr.t("agentpim.group.$g")) {
            for (key in keys) {
                val nas = v.o("nas").str(key, "deny")
                val choices = ownChoices(key, nas)
                Row2(tr.t("agentpim.action.$key"), tr.t("agentpim.mine.now", "mode" to mode(v.o("effective").str(key, nas))) + " · " + tr.t("agentpim.mine.nas", "mode" to mode(nas))) {
                    if (choices.size > 1) Select(choices.map { it to if (it.isEmpty()) tr.t("agentpim.mine.asNas") else mode(it) }, v.o("own").str(key), { value ->
                        val own = v.o("own").toMutableMap()
                        if (value.isEmpty()) own.remove(key) else own[key] = JsonPrimitive(value)
                        saving = true
                        scope.launch { try { view = model.api.sendJson("PUT", "agent/rights/me", jsonOf("own" to JsonObject(own))).obj() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } finally { saving = false } }
                    }, tr.t("agentpim.action.$key"), enabled = !saving)
                }
            }
        }
    }
}

/** Mitteilungen: die Geräte des Kontos (PushDevices) und die Mitteilungen dieses Handys. */
@Composable
private fun Push(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<JsonObject>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    suspend fun load() { try { devices = model.api.getJson("notifications/push").obj().os("devices") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } }
    LaunchedEffect(Unit) { load() }
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr.t("agent.set.pushHint"), style = AgentText.sm, color = c.muted)
            Grp {
                val app = if (tr.de) "Diese App" else "This app"
                Row2(app, if (tr.de) "Mitteilungen des KI-Agenten kommen über die TitanOS-App (ohne Google). Hier stellst du ein, wie Android sie zeigt." else "The agent's notifications arrive through the TitanOS app (without Google). Choose here how Android shows them.") {
                    PillButton(tr.t("agent.set.push"), {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, icon = "bell", small = true)
                }
            }
            message?.let { Notice(it, tone = "ok") }
            error?.let { Notice(it, tone = "error") }
            SGroup(tr.t("push.title")) {
                val list = devices
                if (list != null && list.isEmpty()) Text(tr.t("push.none"), style = AgentText.md, color = c.muted)
                for (d in list.orEmpty()) Column(Modifier.fillMaxWidth().clip(io.github.ra5on.titanos.agent.ui.SRowShape).background(io.github.ra5on.titanos.agent.ui.AgentColors.srowBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.str("name").ifEmpty { d.str("service") }, style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(tr.t("push.since", "date" to dateTime(d.str("created"))), style = AgentText.sm, color = c.muted)
                    val off = d.strs("off")
                    for ((kind, label) in listOf("nas" to "push.kindNas", "agent" to "push.kindAgent", "camera" to "push.kindCamera", "mail" to "push.kindMail")) {
                        if (kind == "nas" && model.status?.admin != true) continue
                        SwitchRow(tr.t(label), null, kind !in off) { on ->
                            val next = if (on) off - kind else off + kind
                            scope.launch {
                                try { model.api.sendJson("PUT", "notifications/push/${d.str("id")}", jsonOf("nas" to ("nas" !in next), "agent" to ("agent" !in next), "camera" to ("camera" !in next), "mail" to ("mail" !in next))); load() }
                                catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
                            }
                        }
                    }
                    Buttons {
                        PillButton(tr.t("push.test"), { scope.launch { try { model.api.sendJson("POST", "notifications/push/${d.str("id")}/test"); message = tr.t("push.testSent", "name" to d.str("name")) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } } }, small = true)
                        PillButton(tr.t("push.remove"), { removing = d }, kind = Btn.Danger, small = true)
                    }
                }
            }
        }
        AgentDialog(removing != null, tr.t("push.remove"), { removing = null }, text = tr.t("push.removeText", "name" to (removing?.str("name") ?: "")), actions = {
            PillButton(tr.t("push.remove"), { val id = removing?.str("id"); removing = null; scope.launch { runCatching { model.api.sendJson("DELETE", "notifications/push/$id") }; load() } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { removing = null })
        })
    }
}

private val DOT_COLORS = listOf("orange" to "#d9734a", "red" to "#e5484d", "pink" to "#e93d82", "violet" to "#8e4ec6", "blue" to "#3e63dd", "teal" to "#12a594", "green" to "#30a46c", "yellow" to "#e2a336")

/** Farbe wählen (ColorPick.svelte): automatisch, acht Farben, eigene (#rrggbb). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPick(value: String, onChange: (String) -> Unit, autoKey: String, label: String) {
    val tr = tr()
    val c = Titan.colors
    var custom by remember(value) { mutableStateOf(if (DOT_COLORS.none { it.second == value }) value else "") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val auto = value.isEmpty()
            Box(Modifier.heightIn(min = 36.dp).clip(CircleShape).background(if (auto) c.accent.copy(alpha = 0.16f) else Color.Transparent).border(1.dp, if (auto) c.accent else c.border, CircleShape)
                .clickable(role = Role.RadioButton) { onChange("") }.semantics { selected = auto }.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(tr.t(autoKey), style = AgentText.sm, color = c.text) }
            for ((k, hex) in DOT_COLORS) {
                val on = value == hex
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(hexColor(hex, AgentWarm)).border(if (on) 3.dp else 0.dp, c.text, CircleShape)
                        .clickable(role = Role.RadioButton) { onChange(hex) }.semantics { selected = on; contentDescription = tr.or("agent.set.color.$k", k) },
                    contentAlignment = Alignment.Center,
                ) { if (on) WIcon("check", 16.dp, Color.White) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr.t("agent.set.colorCustom"), style = AgentText.sm, color = c.muted)
            Field(custom, { s -> custom = s.take(7); if (Regex("^#[0-9a-fA-F]{6}$").matches(s)) onChange(s.lowercase()) }, Modifier.width(140.dp), placeholder = "#rrggbb", label = label)
        }
    }
}

/** Darstellung (look im Web), mit Vorschau der Figur in Farbe und Größe. */
@Composable
private fun LookPart(model: AgentModel, clock: FigureClock) {
    val tr = tr()
    val c = Titan.colors
    val look = model.look
    fun set(l: Look) = model.changeLook(l)
    val context = LocalContext.current
    val ap = io.github.ra5on.titanos.agent.core.Appearance
    val de = tr.de
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Grp(if (de) "Thema und Farben" else "Theme and colours") {
            Row2(if (de) "Thema" else "Theme", if (de) "Ab Werk hell oder dunkel wie das Handy; „Wie mein Konto“ nimmt das Thema aus TitanOS › Darstellung." else "By default light or dark like the phone; “Like my account” takes the theme from TitanOS › Appearance.") {
                Select(listOf("system" to (if (de) "Wie das Handy" else "Like the phone"), "account" to (if (de) "Wie mein Konto" else "Like my account"), "graphit" to "Graphit", "schwarz" to (if (de) "Schwarz" else "Black"), "mitternacht" to (if (de) "Mitternacht" else "Midnight"), "hell" to (if (de) "Hell" else "Light")), ap.theme, { ap.theme = it; ap.save(context) }, if (de) "Thema" else "Theme")
            }
            Row2(if (de) "Akzent" else "Accent") {
                Select(listOf("account" to (if (de) "Wie mein Konto" else "Like my account"), "blau" to (if (de) "Blau" else "Blue"), "gruen" to (if (de) "Grün" else "Green"), "tuerkis" to (if (de) "Türkis" else "Turquoise"), "violett" to (if (de) "Violett" else "Violet"), "kupfer" to (if (de) "Kupfer" else "Copper"), "rose" to "Rosé"), ap.accent, { ap.accent = it; ap.save(context) }, if (de) "Akzent" else "Accent")
            }
            Row2(if (de) "Schriftgröße der App" else "Text size of the app", "${(ap.fontScale * 100).toInt()} % · " + tr.t("agent.set.device")) {
                Range(ap.fontScale, 0.9f..2f, { ap.fontScale = Math.round(it * 20) / 20f }, "${(ap.fontScale * 100).toInt()} %", steps = 21, onDone = { ap.save(context) })
            }
        }
        Grp {
            Row2(tr.t("agent.set.motionLabel")) { Segments(listOf("full", "reduced", "off").map { it to tr.t("agent.set.motion.$it") }, look.motion, { set(look.copy(motion = it)) }, fill = true) }
            Row2(tr.t("agent.set.fontSize"), tr.t("agent.set.device")) { Segments(listOf("s", "m", "l").map { it to tr.t("agent.set.font.$it") }, look.fontSize, { set(look.copy(fontSize = it)) }, fill = true) }
            Row2(tr.t("agent.set.chatWidth"), tr.t("agent.set.chatWidthHint")) { Segments(listOf("full", "read").map { it to tr.t("agent.set.chatWidth.$it") }, look.chatWidth, { set(look.copy(chatWidth = it)) }, fill = true) }
            SwitchRow(tr.t("agent.set.compact"), null, look.compact) { set(look.copy(compact = it)) }
        }
        Grp {
            Row2(tr.t("agent.set.dotColor")) { ColorPick(look.dotColor, { set(look.copy(dotColor = it)) }, "agent.set.colorAutoDots", tr.t("agent.set.dotColor")) }
            Row2(tr.t("agent.set.textColor")) { ColorPick(look.textColor, { set(look.copy(textColor = it)) }, "agent.set.colorAutoText", tr.t("agent.set.textColor")) }
            Row2(tr.t("agent.set.dotSize")) { Segments(listOf("s", "m", "l").map { it to tr.t("agent.set.dotSize.$it") }, look.dotSize, { set(look.copy(dotSize = it)) }, fill = true) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = tr.t("agent.set.colorPreview") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BusyFigure("", size = look.dotDp.dp, color = hexColor(look.dotColor, AgentWarm), still = look.motion == "off", clock = clock)
                Text(tr.t("agent.busy.1"), style = AgentText.md, color = hexColor(look.textColor, c.muted))
            }
        }
        Grp(tr.t("agent.set.inChat")) {
            SwitchRow(tr.t("agent.set.suggestions"), null, look.suggestions) { set(look.copy(suggestions = it)) }
            SwitchRow(tr.t("agent.set.steps"), null, look.steps) { set(look.copy(steps = it)) }
            SwitchRow(tr.t("agent.lookTyping"), null, look.typing) { set(look.copy(typing = it)) }
            SwitchRow(tr.t("agent.set.keepAwake"), tr.t("agent.set.device"), look.keepAwake) { set(look.copy(keepAwake = it)) }
        }
    }
}

@Composable
private fun Gestures(model: AgentModel) {
    val tr = tr()
    val look = model.look
    fun set(l: Look) = model.changeLook(l)
    Grp {
        SwitchRow(tr.t("agent.set.gesturesLabel"), tr.t("agent.set.gesturesHint"), look.gestures) { set(look.copy(gestures = it)) }
        Row2(tr.t("agent.set.swipeRight")) { Select(Look.SWIPES.map { it to tr.t("agent.set.swipe.$it") }, look.swipeRight, { set(look.copy(swipeRight = it)) }, tr.t("agent.set.swipeRight"), enabled = look.gestures) }
        Row2(tr.t("agent.set.swipeLeft")) { Select(Look.SWIPES.map { it to tr.t("agent.set.swipe.$it") }, look.swipeLeft, { set(look.copy(swipeLeft = it)) }, tr.t("agent.set.swipeLeft"), enabled = look.gestures) }
        SwitchRow(tr.t("agent.set.edge"), tr.t("agent.set.edgeHint"), look.edge, enabled = look.gestures) { set(look.copy(edge = it)) }
        SwitchRow(tr.t("agent.set.pull"), null, look.pull) { set(look.copy(pull = it)) }
        SwitchRow(tr.t("agent.lookVibrate"), tr.t("agent.set.device"), look.vibrate) { set(look.copy(vibrate = it)) }
    }
}

/** Über die App und Entkoppeln (nur in der App; die Kopplung selbst baut die Hauptsitzung). */
@Composable
private fun About(model: AgentModel, hooks: AppHooks) {
    val tr = tr()
    var ask by remember { mutableStateOf(false) }
    Box {
        Grp {
            Row2(if (tr.de) "Version" else "Version", hooks.versionName) {}
            Row2("NAS", listOfNotNull(hooks.hostname.ifEmpty { null }, model.status?.version).joinToString(" · ").ifEmpty { "–" }) {}
            Row2(if (tr.de) "Konto" else "Account", hooks.user.ifEmpty { "–" }) {}
            Row2(if (tr.de) "Entkoppeln" else "Unpair", if (tr.de) "Die App vergisst das NAS, ihren Schlüssel und den Zwischenspeicher." else "The app forgets the NAS, its key and its cache.") {
                PillButton(if (tr.de) "Entkoppeln" else "Unpair", { ask = true }, kind = Btn.Danger, small = true)
            }
        }
        AgentDialog(ask, if (tr.de) "Entkoppeln" else "Unpair", { ask = false }, text = if (tr.de) "Die App vergisst das NAS. Neu koppeln geht jederzeit." else "The app forgets the NAS. You can pair again at any time.", actions = {
            PillButton(if (tr.de) "Entkoppeln" else "Unpair", { ask = false; hooks.onUnpair() }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { ask = false })
        })
    }
}


/** Die Rollen im System: digitaler Assistent, Notizen-App, Antworten als Mitteilung (auch Android Auto). */
@Composable
private fun Assistant() {
    val tr = tr()
    val c = Titan.colors
    val context = LocalContext.current
    val roles = context.getSystemService(android.app.role.RoleManager::class.java)
    var tick by remember { mutableStateOf(0) }
    val assistant = remember(tick) { roles?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true }
    val notes = remember(tick) { android.os.Build.VERSION.SDK_INT >= 34 && roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_NOTES) == true && roles.isRoleHeld(android.app.role.RoleManager.ROLE_NOTES) }
    val notesAvailable = android.os.Build.VERSION.SDK_INT >= 34 && roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_NOTES) == true
    val askRole = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { tick++ }
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { tick++ }
    var screen by remember { mutableStateOf(io.github.ra5on.titanos.agent.system.AssistScreen.get(context)) }
    var answers by remember { mutableStateOf(io.github.ra5on.titanos.agent.system.AnswerNotifier.enabled(context)) }
    val de = tr.de
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Grp(if (de) "Digitaler Assistent" else "Digital assistant") {
            Row2(if (de) "Agent als Assistent" else "Agent as assistant", if (assistant) (if (de) "Gewählt: Ein-Taste lang drücken oder aus der Ecke wischen öffnet das Blatt des Agenten mit Spracheingabe." else "Chosen: long-press the power button or swipe from the corner to open the agent's sheet with voice input.") else (if (de) "In den Android-Einstellungen unter „Standard-Apps › Digitaler Assistent“ wählen." else "Choose it in Android settings under “Default apps › Digital assistant”.")) {
                Tag(if (assistant) tr.t("agent.on") else tr.t("agent.off"), tone = if (assistant) "ok" else "")
                PillButton(if (de) "Auswählen" else "Choose", { runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; tick++ }, small = true)
            }
            Row2(if (de) "Bildschirminhalt" else "Screen content", if (de) "Ob der Assistent Text und Bild des Bildschirms mitnehmen darf. Er geht nur an dein NAS." else "Whether the assistant may take the screen's text and picture. It only goes to your NAS.") {
                Segments(listOf(io.github.ra5on.titanos.agent.system.AssistScreen.NEVER to (if (de) "Nie" else "Never"), io.github.ra5on.titanos.agent.system.AssistScreen.ASK to (if (de) "Jedes Mal fragen" else "Ask every time"), io.github.ra5on.titanos.agent.system.AssistScreen.ALWAYS to (if (de) "Immer" else "Always")), screen, { screen = it; io.github.ra5on.titanos.agent.system.AssistScreen.set(context, it) }, fill = true)
            }
        }
        Grp(if (de) "Notizen-App" else "Notes app") {
            Row2(if (de) "Notizen des Agenten als Notizen-App" else "Agent notes as notes app", if (!notesAvailable) (if (de) "Dieses Gerät bietet die Notizen-Rolle nicht an (Android 14 und ein Stift oder Tablet nötig)." else "This device does not offer the notes role (needs Android 14 and a stylus or tablet).") else (if (de) "Stift-Taste und Sperrbildschirm öffnen eine neue Notiz." else "The stylus button and the lock screen open a new note.")) {
                Tag(if (notes) tr.t("agent.on") else tr.t("agent.off"), tone = if (notes) "ok" else "")
                if (notesAvailable && !notes && android.os.Build.VERSION.SDK_INT >= 34) PillButton(if (de) "Auswählen" else "Choose", { roles?.let { askRole.launch(it.createRequestRoleIntent(android.app.role.RoleManager.ROLE_NOTES)) } }, small = true)
            }
        }
        Grp(if (de) "Mitteilungen und Android Auto" else "Notifications and Android Auto") {
            SwitchRow(if (de) "Antworten als Mitteilung" else "Answers as notifications", if (de) "Kommt eine Antwort, während die App nicht vorne ist, zeigt Android sie als Nachricht – im Auto liest Android Auto sie vor, und du antwortest per Sprache." else "When an answer arrives while the app is not in front, Android shows it as a message – in the car Android Auto reads it aloud and you reply by voice.", answers) { on ->
                answers = on
                io.github.ra5on.titanos.agent.system.AnswerNotifier.setEnabled(context, on)
                if (on && !io.github.ra5on.titanos.agent.system.AnswerNotifier.allowed(context)) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            Text(if (de) "Ohne Play Store: in Android Auto die Entwickleroptionen öffnen (zehnmal auf die Version tippen) und „Unbekannte Quellen“ einschalten." else "Without the Play Store: open the developer options of Android Auto (tap the version ten times) and switch on “Unknown sources”.", style = AgentText.sm, color = c.muted)
        }
    }
}
