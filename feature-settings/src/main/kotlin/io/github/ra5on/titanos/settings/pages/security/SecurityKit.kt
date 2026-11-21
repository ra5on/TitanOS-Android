package io.github.ra5on.titanos.settings.pages.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonObject

// Kleine Hilfen der Seiten „Sicherheit und Konten“ (Zugang, API, Cluster, …).
// Gehörten eigentlich nach common/ – siehe Bericht.

internal fun JsonObject?.objs(key: String): List<JsonObject> = a(key).objects()

/** „Ein“/„Aus“ der Schalter (Android-Texte von core-ui; das Web zeigt nur den Schalter). */
@Composable
internal fun onText(): String = stringResource(io.github.ra5on.titanos.ui.R.string.ui_on)

@Composable
internal fun offText(): String = stringResource(io.github.ra5on.titanos.ui.R.string.ui_off)

/** Fehlercode einer API-Antwort, sonst null. */
internal val Throwable.apiCode: String? get() = (this as? ApiException)?.code

/** Die Meldung des NAS hinter dem Code („HTTP 400 code: Text“ → „Text“). */
internal val Throwable.apiDetail: String get() = (this as? ApiException)?.message?.substringAfter(": ", "").orEmpty()

/**
 * Legt Text in die Zwischenablage. Geheimnisse ([sensitive]) werden als
 * vertraulich markiert, damit Android sie nicht in der Vorschau zeigt.
 */
internal fun copyText(context: Context, text: String, sensitive: Boolean = false) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("TitanOS", text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}

/**
 * Die Adresse des NAS, über die die App spricht (wie `location.origin` im
 * Web), z. B. für die MCP-Adresse. SettingsApi verrät sie nicht; die Verbindung
 * wird über ihren Typ gefunden (überlebt Umbenennen beim Verkleinern).
 */
internal fun SettingsApi.origin(): String = runCatching {
    val field = SettingsApi::class.java.declaredFields.first { it.type == NasConnection::class.java }
    field.isAccessible = true
    (field.get(this) as NasConnection).endpoints.first().base.toString().trimEnd('/')
}.getOrDefault("")

/** Ein Knopf eines [FormDialog]. */
internal data class FormAction(val label: String, val kind: ButtonKind = ButtonKind.Normal, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Dialog mit Feldern (wie Dialog.svelte mit Formular): Titel, Text, Inhalt,
 * Knöpfe rechts – der gefährliche bzw. wichtige zuerst, „Abbrechen“ zuletzt.
 * Nichts löst per Enter oder Autofokus aus; der Inhalt scrollt, wenn er nicht passt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.FormDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: List<FormAction>,
    text: String? = null,
    locked: Boolean = false,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val dismiss = { if (!locked) onDismiss() }
    BackHandler(onBack = dismiss)
    val c = Titan.colors
    androidx.compose.foundation.layout.Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss),
    )
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 620.dp).fillMaxWidth().clip(shape).background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text)
        if (text != null) Text(text, style = TitanType.body, color = c.muted)
        body()
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in actions) SmallButton(a.label, a.onClick, kind = a.kind, enabled = a.enabled)
        }
    }
}

/** Beschriftetes Feld (Name darüber, wie `<label class="stack">`). */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    mono: Boolean = false,
    enabled: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TitanType.rowSub, color = Titan.colors.text)
        Field(value, onChange, label, Modifier.fillMaxWidth(), placeholder = placeholder, password = password, mono = mono, enabled = enabled, minHeight = minHeight, keyboard = keyboard)
    }
}

/**
 * Passwort und – mit Zwei-Faktor-Anmeldung – Code (ReconfirmFields.svelte):
 * der Nachweis, den das NAS vor einem neuen Zugang ins Konto verlangt.
 */
@Composable
internal fun ReconfirmFields(password: String, onPassword: (String) -> Unit, code: String, onCode: (String) -> Unit, twoFactor: Boolean, enabled: Boolean = true) {
    Text(t(if (twoFactor) "reconfirm.hintCode" else "reconfirm.hint"), style = TitanType.rowSub, color = Titan.colors.muted)
    LabeledField(t("auth.password"), password, onPassword, password = true, enabled = enabled)
    if (twoFactor) LabeledField(t("auth.code"), code, onCode, mono = true, enabled = enabled, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
}

internal fun reconfirmComplete(password: String, code: String, twoFactor: Boolean): Boolean =
    password.isNotEmpty() && (!twoFactor || code.isNotBlank())

/** Ein Wert rechts neben seinem Namen; bricht um, nie gekürzt, markierbar. */
@Composable
internal fun Value(text: String, mono: Boolean = false, muted: Boolean = false) {
    SelectionContainer {
        Text(
            text,
            style = if (mono) TitanType.rowSub.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.rowSub,
            color = if (muted) Titan.colors.muted else Titan.colors.text,
        )
    }
}

/** Gedämpfter Satz innerhalb einer aufgeklappten Zeile (`.srow-block .muted`). */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = modifier.padding(vertical = 4.dp))
}

/** Ein Block Text in Festbreitenschrift mit Rahmen (z. B. ein Befehl zum Kopieren); bricht um. */
@Composable
internal fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    SelectionContainer(modifier.fillMaxWidth().clip(shape).background(c.track).border(1.dp, c.border, shape).padding(12.dp)) {
        Text(text, style = TitanType.rowSub.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = c.text)
    }
}

/** Mehrere Knöpfe nebeneinander, die umbrechen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Buttons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** Ein Text als Verweis (unterstrichen, Akzentfarbe), z. B. „Mein Konto“. */
@Composable
internal fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TitanType.rowSub.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        color = Titan.colors.accent,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(vertical = 8.dp),
    )
}

/** Eine Liste ganzer Zahlen (z. B. `choices`). */
internal fun JsonObject?.ints(key: String): List<Int> = a(key).mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
