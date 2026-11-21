package io.github.ra5on.titanos.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.ra5on.titanos.settings.common.Loaded
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.generated.QrColors
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.serialization.json.JsonObject

/** Was alle Teile von „Mein Konto“ brauchen: die Anfragen, wer angemeldet ist, ob 2FA an ist. */
@Stable
internal class AccountCtx(
    val calls: AccountCalls,
    val name: String,
    val role: String,
    val twoFactor: Boolean,
    /** Liest die Sitzung neu (nach dem Ein- und Ausschalten von 2FA). */
    val refreshSession: suspend () -> Unit,
    /** Die angemeldeten Geräte (auch für die Android-Apps, die dort als Sitzung stehen). */
    val sessions: Loaded<JsonObject>,
) {
    val admin: Boolean get() = role == "owner" || role == "admin"
}

/**
 * Ein Dialog wie `Dialog.svelte`: Titel, Text, Inhalt, Knöpfe unten rechts
 * (der gefährliche links von „Abbrechen“). Der Inhalt scrollt, wenn er bei
 * großer Schrift nicht passt; nichts löst einen Knopf ohne Tippen aus.
 */
@Composable
internal fun BoxScope.AccountDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: List<DialogAction>,
    text: String? = null,
    locked: Boolean = false,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    BackHandler(onBack = { if (!locked) onDismiss() })
    val c = Titan.colors
    val closeLabel = t("common.close")
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!locked) onDismiss() }
            .semantics { contentDescription = closeLabel },
    )
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.align(Alignment.Center).imePadding().padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 560.dp).fillMaxWidth()
            .clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text)
        if (text != null) Text(text, style = TitanType.body, color = c.muted)
        body()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (a in actions) {
                Text(
                    a.label,
                    style = TitanType.button.copy(fontSize = TitanType.body.fontSize),
                    color = if (a.danger) c.dangerInk else c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = a.onClick)
                        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Ob Passwort und (mit 2FA) Code da sind (`reconfirmComplete` im Web). */
internal fun reconfirmComplete(twoFactor: Boolean, password: String, code: String): Boolean =
    password.isNotEmpty() && (!twoFactor || code.isNotBlank())

/** Passwort und mit 2FA ein Code (ReconfirmFields.svelte): der Nachweis vor einem neuen Weg ins Konto. */
@Composable
internal fun ReconfirmFields(twoFactor: Boolean, password: String, onPassword: (String) -> Unit, code: String, onCode: (String) -> Unit, hint: Boolean = true) {
    val c = Titan.colors
    if (hint) Text(t(if (twoFactor) "reconfirm.hintCode" else "reconfirm.hint"), style = TitanType.rowSub, color = c.muted)
    Labeled(t("auth.password")) { Field(password, onPassword, t("auth.password"), Modifier.fillMaxWidth(), password = true) }
    if (twoFactor) CodeField(code, onCode)
}

@Composable
internal fun CodeField(code: String, onCode: (String) -> Unit, label: String = t("auth.code")) {
    Labeled(label) {
        Field(code, onCode, label, Modifier.fillMaxWidth(), mono = true, singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false))
    }
}

/** Beschriftung über einem Feld (`<label><span>…</span><input>`). */
@Composable
internal fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TitanType.rowSub, color = Titan.colors.muted)
        content()
    }
}

/** Text in Mono, der überall umbrechen darf (Schlüssel, Abdrücke, Geheimnisse). */
@Composable
internal fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = Titan.colors.text, big: Boolean = false) {
    Text(text, style = (if (big) TitanType.body else TitanType.rowSub).copy(fontFamily = TitanFonts.mono), color = color, modifier = modifier)
}

/** Ein QR-Code, immer dunkel auf hell (Scanner erwarten das), gezeichnet aus zxing. */
@Composable
internal fun QrImage(text: String, label: String, size: Dp = 200.dp) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        }.getOrNull()
    }
    val shape = RoundedCornerShape(10.dp)
    Canvas(Modifier.size(size).clip(shape).background(QrColors.light).semantics { contentDescription = label }) {
        val m = matrix ?: return@Canvas
        val quiet = 2
        val cells = m.width + quiet * 2
        val cell = this.size.width / cells
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(QrColors.dark, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** Ein Teil einer Zeile ohne eigenen Titel (`.srow-block`). */
@Composable
internal fun Block(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Knöpfe nebeneinander, die umbrechen. */
@Composable
internal fun Buttons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Gedämpfter Satz. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = modifier)

/** Ein angeklickter Link im Text (`<a>`), als eigener Knopf mit Namen. */
@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = TitanType.rowSub.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        color = Titan.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 32.dp).padding(vertical = 6.dp),
    )
}

/** Mehrere Bedienelemente rechts in einer Zeile: brechen um statt sich zu quetschen. */
@Composable
internal fun Controls(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
