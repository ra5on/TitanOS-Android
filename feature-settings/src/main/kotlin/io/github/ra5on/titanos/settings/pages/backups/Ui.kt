package io.github.ra5on.titanos.settings.pages.backups

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Kleine Bausteine der Sicherungsseiten, die core-ui (noch) nicht hat:
// ein scrollender Dialog, Hinweiskästen mit beliebigem Inhalt, „Einzelheit“,
// Uhrzeit-Auswahl, Beschriftung über Feldern, Kopieren, Passwort erneut
// eingeben und „Die letzten N behalten“.

/**
 * Dialog (.dialog im Web), dessen Inhalt scrollt, wenn er nicht passt (200 %
 * Schrift, Tastatur). [locked]: während ein Vorgang läuft, schließt weder
 * Tippen daneben noch der Pfeil zurück.
 */
@Composable
internal fun BoxScope.BkDialog(
    title: String,
    onDismiss: () -> Unit,
    locked: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { if (!locked) onDismiss() }
    val c = Titan.colors
    val closeLabel = t("common.close")
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!locked) onDismiss() }
            .semantics { contentDescription = closeLabel },
    )
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.align(Alignment.Center).imePadding().padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 620.dp).fillMaxWidth()
            .clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text, modifier = Modifier.semantics { heading() })
        content()
        if (actions != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

/** Hinweiskasten (.notice-*) mit beliebigem Inhalt; immer mit Symbol, nie nur Farbe. */
@Composable
internal fun Notice(tone: Tone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Titan.colors
    val (bg, border, _) = when (tone) {
        Tone.Warn -> Triple(c.warnBg, c.warnBorder, c.warnText)
        Tone.Error -> Triple(c.errorBg, c.errorBorder, c.errorText)
        Tone.Ok -> Triple(c.okBg, c.okBorder, c.okText)
        else -> Triple(c.accent.copy(alpha = .12f).compositeOver(c.surface), c.accent.copy(alpha = .35f).compositeOver(c.border), c.text)
    }
    val icon = when (tone) {
        Tone.Warn, Tone.Error -> TitanIcons.warn
        Tone.Ok -> TitanIcons.check
        else -> TitanIcons.info
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(bg).border(1.dp, border, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (tone == Tone.Info || tone == Tone.Inactive) c.accent else noticeInk(tone), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun noticeInk(tone: Tone): Color = when (tone) {
    Tone.Warn -> Titan.colors.warnText
    Tone.Error -> Titan.colors.errorText
    Tone.Ok -> Titan.colors.okText
    else -> Titan.colors.text
}

/** Ein Satz im Hinweiskasten. */
@Composable
internal fun NoticeText(text: String, tone: Tone) = Text(text, style = TitanType.body, color = noticeInk(tone))

/** Ein Hinweis mit einem Satz und wahlweise der Einzelheit des NAS. */
@Composable
internal fun NoticeLine(text: String, tone: Tone, detail: String? = null) {
    Notice(tone) {
        NoticeText(text, tone)
        if (!detail.isNullOrBlank()) BackendDetail(detail, tone)
    }
}

/** Was das NAS in eigenen Worten sagte, hinter „Einzelheit“ (BackendDetail.svelte). */
@Composable
internal fun BackendDetail(text: String?, tone: Tone = Tone.Info) {
    if (text.isNullOrBlank()) return
    var open by remember { mutableStateOf(false) }
    val c = Titan.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            t("detail.label") + if (open) " ▾" else " ▸",
            style = TitanType.rowSub,
            color = if (tone == Tone.Info) c.accent else noticeInk(tone),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "▾" else "▸" }.heightIn(min = 32.dp).padding(vertical = 6.dp),
        )
        if (open) Mono(text, color = if (tone == Tone.Info) c.text else noticeInk(tone))
    }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = modifier)

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier) = Text(text, style = TitanType.body, color = Titan.colors.text, modifier = modifier)

@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Titan.colors.text) =
    Text(text, style = TitanType.rowSub.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = color, modifier = modifier)

/** Eine kleine Überschrift in einer Fläche (h3 im Web). */
@Composable
internal fun SubHead(text: String, modifier: Modifier = Modifier) =
    Text(text, style = TitanType.section, color = Titan.colors.text, modifier = modifier.padding(top = 8.dp).semantics { heading() })

/** Eine Überschrift wie `.sgroup-title` ohne Zeilen darunter. */
@Composable
internal fun GroupTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = Titan.colors.text, modifier = modifier.padding(top = 18.dp, start = 2.dp, bottom = 6.dp).semantics { heading() })

/** Beschriftung über einem Feld (label > span im Web). */
@Composable
internal fun Labeled(label: String, modifier: Modifier = Modifier, hint: String? = null, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Muted(label)
        content()
        if (hint != null) Muted(hint)
    }
}

/** Eine Fläche wie `.srow.panel` (Kachel eines Auftrags, Formular). */
@Composable
internal fun Panel(modifier: Modifier = Modifier, border: Color = Titan.colors.border, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(Titan.colors.surface).border(1.dp, border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Ein Abschnitt eines Formulars (fieldset.divided): Überschrift und Inhalt. */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        io.github.ra5on.titanos.ui.components.Divider()
        SubHead(title)
        content()
    }
}

/**
 * Uhrzeit (input type=time): zeigt „HH:MM“, Tippen öffnet die Uhr des
 * Systems im 24-Stunden-Format.
 */
@Composable
internal fun TimeField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    val c = Titan.colors
    Row(
        Modifier.heightIn(min = 44.dp).clip(shape).background(c.bg).border(1.dp, c.border, shape)
            .clickable(enabled = enabled, role = Role.Button) {
                val (h, m) = value.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                TimePickerDialog(context, { _, hh, mm -> onChange("%02d:%02d".format(hh, mm)) }, h, m, true).show()
            }
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = value }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(WebIcons["clock"], null, tint = c.muted, modifier = Modifier.size(18.dp))
        Mono(value.ifEmpty { "––:––" }, color = if (enabled) c.text else c.muted)
    }
}

/** Ein Auswahlkreis mit Text in einer Zeile, ohne eigene Liste (radio in label). */
@Composable
internal fun RadioLine(selected: Boolean, text: String, onClick: () -> Unit, enabled: Boolean = true, desc: String? = null) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .heightIn(min = 44.dp).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected)
        Column(Modifier.weight(1f)) {
            Text(text, style = TitanType.body, color = if (enabled) c.text else c.muted)
            if (desc != null) Muted(desc)
        }
    }
}

/** Legt Text in die Zwischenablage (Kopplungscode, Passwort des Backups). */
internal fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text).apply {
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

/**
 * Ein einmal gezeigtes Geheimnis (Kopplungscode, Passwort eines Backups)
 * mit Kopieren-Knopf; es wird nirgends gespeichert.
 */
@Composable
internal fun SecretBox(value: String, label: String) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(c.bg).border(1.dp, c.border, shape).semantics { contentDescription = label }.padding(12.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer { Mono(value) }
    }
}

/**
 * Passwort (und Code der Anmeldung in zwei Schritten) erneut eingeben,
 * bevor etwas die Geheimnisse des NAS zeigt (ConfirmDialog.svelte):
 * `POST session/confirm`, danach [onConfirmed].
 */
@Composable
internal fun BoxScope.ConfirmPasswordDialog(reason: String, onConfirmed: () -> Unit, onClose: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session = rememberLoad { BkApi.session(env.api) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val twoFactor = session.value?.o("user")?.bool("twoFactor") == true
    BkDialog(t("confirm.title"), onClose, locked = working, actions = {
        SmallButton(t("common.cancel"), onClose, enabled = !working)
        SmallButton(t("confirm.submit"), {
            working = true
            error = ""
            scope.launch {
                try {
                    BkApi.confirmSession(env.api, password, code)
                    password = ""
                    onConfirmed()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e)
                    working = false
                }
            }
        }, kind = ButtonKind.Primary, enabled = !working && password.isNotEmpty() && (!twoFactor || code.isNotEmpty()))
    }) {
        Muted(reason)
        Labeled(t("auth.password")) { Field(password, { password = it }, t("auth.password"), Modifier.fillMaxWidth(), password = true) }
        if (twoFactor) {
            Labeled(t("auth.code")) {
                Field(code, { code = it.filter { ch -> ch.isDigit() } }, t("auth.code"), Modifier.fillMaxWidth(), mono = true, keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
            }
        }
        if (session.value == null && session.loading) LoadingLine(t("common.loading"))
        if (error.isNotEmpty()) NoticeLine(error, Tone.Warn)
    }
}

/**
 * „Die letzten [N] Sicherungen behalten“ und, wo es passt, „Behalten, bis
 * … voll ist“ (KeepVersions.svelte). [keep] wie `job.keep`.
 */
@Composable
internal fun KeepVersions(
    keep: JsonObject,
    onChange: (JsonObject) -> Unit,
    target: String = "storage",
    unit: String = "backups",
    rolling: Boolean = true,
    min: Long = 1,
    max: Long = 9999,
) {
    val isRolling = rolling && keep["mode"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "rolling"
    val c = Titan.colors
    val lastLabel = t("keep.lastBefore")
    @Composable
    fun count() {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (rolling) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.RadioButton) { onChange(keep.with("mode" to "")) }.semantics { contentDescription = lastLabel }.padding(4.dp)) {
                        RadioMark(!isRolling)
                    }
                }
                Body(t("keep.lastBefore"))
                NumberField(
                    Bk.lastOf(keep),
                    { n -> if (n != null) onChange(keep.with("last" to n.coerceIn(min, max))) },
                    t("keep.lastBefore") + " " + t("keep.lastAfter.$unit"),
                    enabled = !isRolling,
                )
                Body(t("keep.lastAfter.$unit"))
            }
            Muted(t("keep.lastHint.$unit"), Modifier.padding(start = if (rolling) 36.dp else 0.dp))
        }
    }
    if (!rolling) {
        count()
        return
    }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, if (!isRolling) c.accent else c.border, shape).padding(horizontal = 14.dp, vertical = 12.dp)) { count() }
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, if (isRolling) c.accent else c.border, shape).padding(horizontal = 14.dp, vertical = 8.dp)) {
            RadioLine(isRolling, t("keep.rolling.$target"), { onChange(keep.with("mode" to "rolling")) })
            Muted(t("keep.rollingHint.${if (target == "replica") "replica" else unit}"), Modifier.padding(start = 36.dp))
        }
    }
}


/** Knöpfe, einer gewählt (Segmente), einzelne auch gesperrt; brechen um statt zu kürzen. */
@Composable
internal fun <T> SegmentRow(value: T, options: List<Triple<T, String, Boolean>>, onChange: (T) -> Unit) {
    val c = Titan.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, text, enabled) in options) {
            val on = v == value
            val shape = RoundedCornerShape(10.dp)
            Text(
                text,
                style = TitanType.body,
                color = if (on) c.onAccent else if (enabled) c.text else c.muted,
                modifier = Modifier.clip(shape).background(if (on) c.accent else c.chip)
                    .border(1.dp, if (on) c.accent else c.border, shape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onChange(v) }
                    .semantics { stateDescription = if (on) "✓" else "" }
                    .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

/** Ein kleines Ankreuzfeld mit kurzem Text, für Reihen wie die Wochentage. */
@Composable
internal fun CheckChip(text: String, checked: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).background(if (checked) c.accent.copy(alpha = .18f).compositeOver(c.surface) else c.chip)
            .border(1.dp, if (checked) c.accent else c.border, shape)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "☑" else "☐" }
            .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (checked) Icon(TitanIcons.check, null, tint = c.accent, modifier = Modifier.size(16.dp))
        Text(text, style = TitanType.body, color = if (enabled) c.text else c.muted)
    }
}

/** Ein Knopf, der einen Bereich auf- und zuklappt (details/summary im Web). */
@Composable
internal fun Disclosure(title: String, open: Boolean, onToggle: () -> Unit) {
    Text(
        (if (open) "▾ " else "▸ ") + title,
        style = TitanType.body,
        color = Titan.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (open) "▾" else "▸" }.heightIn(min = 40.dp).padding(vertical = 9.dp),
    )
}

/** Ein Ankreuzfeld allein (ohne Text daneben), mit Namen für Bildschirmleser. */
@Composable
internal fun CheckBox(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val c = Titan.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .semantics { contentDescription = label; stateDescription = if (checked) "☑" else "☐" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).clip(shape).background(if (checked) c.accent else Color.Transparent).border(2.dp, if (checked) c.accent else c.muted, shape), contentAlignment = Alignment.Center) {
            if (checked) Icon(TitanIcons.check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}
