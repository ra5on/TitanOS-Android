package io.github.ra5on.titanos.settings.pages.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Overlays
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Kleine Bausteine der Seiten „Netzwerk und Fernzugriff“ (Netzwerk, Firewall,
// WireGuard, DNS, Zertifikate, HTTPS-Namen). Gemeinsames, das in common/
// fehlt, steht hier privat für diese Gruppe.

/** Die Objekte einer Liste im Objekt. */
internal fun JsonObject?.objs(key: String): List<JsonObject> = (this?.get(key) as? JsonArray)?.objects().orEmpty()

/** Eine Liste von Objekten als JSON. */
internal fun List<JsonElement>.json(): JsonArray = JsonArray(this)

/** Restzeit einer Probe (Netzwerk, Firewall) als m:ss wie trial.ts. */
internal fun countdown(seconds: Long): String {
    val s = maxOf(0L, seconds)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Ob ein Fehler „nicht erreichbar“ ist (keine Antwort des NAS), im Gegensatz zu einer Antwort mit Fehlercode. */
internal fun Throwable.unreachable(): Boolean = this is java.io.IOException && this !is ApiException

/** Ein Fehlertext wie `errorText(code) + message` im Web (für `invalid_*`-Fehler mit Grund). */
internal fun Tr.errorWithReason(e: Throwable, codes: Set<String>, strip: Regex? = null): String {
    val api = e as? ApiException
    if (api != null && api.code in codes) {
        val msg = api.message?.substringAfter(": ", "").orEmpty().let { m -> strip?.let { m.replace(it, "") } ?: m }
        return listOf(error(api.code), msg).filter { it.isNotBlank() }.joinToString(" ")
    }
    return errorOf(e)
}

/** Datum und Uhrzeit eines Zeitstempels oder „–“. */
internal fun Tr.whenOr(text: String?): String = Fmt.dateTime(this, text).ifEmpty { "–" }

/** Öffnet eine Adresse im Browser des Handys. */
internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Kopiert Text in die Zwischenablage (Geheimnisse als „sensibel“ markiert, damit Android sie nicht anzeigt). */
internal fun copyText(context: Context, label: String, text: String, secret: Boolean = false) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (secret) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

/** Schalter mit den Wörtern der App für an/aus. */
@Composable
internal fun Switch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Toggle(
        checked,
        onChange,
        stringResource(io.github.ra5on.titanos.ui.R.string.ui_on),
        stringResource(io.github.ra5on.titanos.ui.R.string.ui_off),
        enabled = enabled,
        label = label,
    )
}

/** Gedämpfter Hinweistext (`.muted.small`). */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = modifier.padding(vertical = 4.dp))
}

/** Text in Mono (Adressen, Schlüssel, Pfade); bricht um. */
@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(text, style = TitanType.rowSub.copy(fontFamily = TitanFonts.mono), color = if (muted) Titan.colors.muted else Titan.colors.text, modifier = modifier)
}

/** Kleine Zwischenüberschrift in einer aufgeklappten Zeile oder einem Dialog. */
@Composable
internal fun SubHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TitanType.body.copy(fontWeight = FontWeight.SemiBold),
        color = Titan.colors.text,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Knöpfe nebeneinander, die umbrechen. */
@Composable
internal fun Buttons(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Eine Angabe (dt/dd): Beschriftung über dem Wert, mehrere Werte untereinander. */
@Composable
internal fun Fact(label: String, values: List<String>, mono: Boolean = false, empty: String = "–") {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = TitanType.rowSub, color = Titan.colors.muted)
        val list = values.filter { it.isNotBlank() }.ifEmpty { listOf(empty) }
        for (v in list) {
            if (mono) Mono(v) else Text(v, style = TitanType.rowSub, color = Titan.colors.text)
        }
    }
}

@Composable
internal fun Fact(label: String, value: String, mono: Boolean = false) = Fact(label, listOf(value), mono)

/** Feld mit Beschriftung darüber (`label.stack`), optional ein Satz darunter. */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    mono: Boolean = false,
    password: Boolean = false,
    enabled: Boolean = true,
    multiLine: Boolean = false,
    number: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = TitanType.rowSub, color = Titan.colors.text)
        io.github.ra5on.titanos.ui.components.Field(
            value,
            onChange,
            label,
            Modifier.fillMaxWidth(),
            placeholder = placeholder,
            password = password,
            mono = mono,
            keyboard = KeyboardOptions(
                keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            enabled = enabled,
            singleLine = !multiLine,
            minHeight = if (multiLine) 84.dp else 44.dp,
        )
        if (hint != null) Text(hint, style = TitanType.rowSub, color = Titan.colors.muted)
    }
}

/** Auswahl mit Beschriftung darüber. */
@Composable
internal fun <T> LabeledSelect(label: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, enabled: Boolean = true, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = TitanType.rowSub, color = Titan.colors.text)
        io.github.ra5on.titanos.ui.components.Select(value, options, onChange, label, enabled = enabled)
        if (hint != null) Text(hint, style = TitanType.rowSub, color = Titan.colors.muted)
    }
}

/** Ein Knopf eines [FormDialog]. */
internal class Act(val label: String, val kind: ButtonKind = ButtonKind.Normal, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Dialog mit Formular, das scrollt, wenn es nicht passt (der einfache
 * Bestätigungsdialog scrollt nicht). Gefährliche Knöpfe stehen links von
 * „Abbrechen“; nichts löst per Enter oder Autofokus aus.
 */
@Composable
internal fun BoxScope.FormDialog(title: String, onDismiss: () -> Unit, actions: List<Act>, body: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val c = Titan.colors
    androidx.compose.foundation.layout.Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
    BoxWithConstraints(Modifier.matchParentSize().padding(horizontal = 16.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight).clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
        ) {
            Text(
                title,
                style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)),
                color = c.text,
                modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = body)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (a in actions) SmallButton(a.label, a.onClick, kind = a.kind, enabled = a.enabled)
            }
        }
    }
}

/** Öffnet einen [FormDialog] über der Seite. */
internal fun Overlays.form(title: String, actions: @Composable (close: () -> Unit) -> List<Act>, body: @Composable ColumnScope.(close: () -> Unit) -> Unit) =
    show { close -> FormDialog(title, close, actions(close)) { body(close) } }

/** Ein Ankreuzfeld, das als Zeile auf Tippen reagiert (für Listen in Dialogen). */
@Composable
internal fun Choice(text: String, checked: Boolean, onChange: (Boolean) -> Unit, desc: String? = null, enabled: Boolean = true) =
    io.github.ra5on.titanos.ui.components.CheckRow(checked, onChange, text, desc = desc, enabled = enabled)

/** Laufende Probe (TrialBanner.svelte): was läuft, wie lange noch, und die zwei Auswege. */
@Composable
internal fun TrialBox(
    title: String,
    hint: String,
    confirmLabel: String,
    revertLabel: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onRevert: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(c.warnBg).border(1.dp, c.warnBorder, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.warnText, modifier = Modifier.semantics { heading() })
        Text(hint, style = TitanType.rowSub, color = c.warnText)
        extra()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(revertLabel, onRevert, enabled = !busy)
            SmallButton(confirmLabel, onConfirm, kind = ButtonKind.Primary, enabled = !busy)
        }
    }
}

/** Ein Kasten mit Text und Knöpfen (z. B. „noch nicht übernommen“). */
@Composable
internal fun ActionBox(title: String?, text: String, tone: Tone = Tone.Warn, buttons: @Composable FlowRowScope.() -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val (bg, border, ink) = when (tone) {
        Tone.Error -> Triple(c.errorBg, c.errorBorder, c.errorText)
        Tone.Ok -> Triple(c.okBg, c.okBorder, c.okText)
        else -> Triple(c.warnBg, c.warnBorder, c.warnText)
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(bg).border(1.dp, border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = ink)
        Text(text, style = TitanType.rowSub, color = ink)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = buttons,
        )
    }
}

/** Ein Fehler oder Hinweis, nur wenn es einen gibt. */
@Composable
internal fun Note(text: String?, tone: Tone = Tone.Error) {
    if (!text.isNullOrBlank()) Banner(text, tone = tone)
}

/** Eine Zeile, die wie ein Knopf woandershin führt, ohne Pfeil-Zeile (Link in einem Text). */
@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold),
        color = Titan.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
    )
}
