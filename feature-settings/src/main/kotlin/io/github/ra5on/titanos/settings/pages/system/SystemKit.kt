package io.github.ra5on.titanos.settings.pages.system

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.Overlays
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.toneInk
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Request

// Gemeinsame Kleinteile der Seiten unter „System“ (nur für dieses Paket).

/**
 * Rohe Inhalte (Bild als Body wie `PUT /app-icon` und `PUT /background`
 * im Web) über [SettingsApi.putBytes], dazu Dateien außerhalb von `/api/v1`
 * (`/icons/app-192.png`, `/backgrounds/<id>.webp`), die [SettingsApi] nicht
 * kann. Wünschenswert wäre `SettingsApi.file(path)` in `common/`.
 */
internal object Raw {
    private fun nas(api: SettingsApi): NasConnection =
        SettingsApi::class.java.getDeclaredField("nas").apply { isAccessible = true }.get(api) as NasConnection

    /** Bytes einer Adresse der Weboberfläche (z. B. `/icons/app-192.png`). */
    suspend fun file(api: SettingsApi, path: String): ByteArray = withContext(Dispatchers.IO) {
        nas(api).execute { base -> Request.Builder().url(base.resolve(path) ?: error("bad path")).build() }.use { r ->
            r.throwIfError()
            r.body.bytes()
        }
    }

    /** Schickt [bytes] roh an `/api/v1/<path>` (Content-Type [mime]); Antwort als JSON. */
    suspend fun send(api: SettingsApi, path: String, bytes: ByteArray, mime: String, method: String = "PUT"): JsonElement =
        api.putBytes(path, bytes, mime, method)
}

/** Für Speichervorgänge, die eine verlassene Seite überdauern sollen (wie `keepalive` im Web). */
internal val Background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Lädt ein Bild der Weboberfläche; null, solange es lädt oder wenn es fehlt. */
@Composable
internal fun rememberWebImage(api: SettingsApi, path: String?): ImageBitmap? {
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        image = runCatching {
            val bytes = if (path.startsWith("/api/v1/")) api.bytes(path.removePrefix("/api/v1/")) else Raw.file(api, path)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }
    return image
}

internal fun decodeImage(bytes: ByteArray?): ImageBitmap? =
    bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }

/** Öffnet eine Adresse der Weboberfläche: Seiten der Systemsteuerung hier, alles andere über die App. */
internal fun PageArgs.go(env: SettingsEnv, path: String) {
    if (path == "/settings" || path.startsWith("/settings/") || path.startsWith("/settings?")) nav.openPath(path) else env.open(path)
}

/** Wert in Mono (Versionen, Kernel), bricht um. */
@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Titan.colors.text) {
    Text(text, style = TitanType.rowSub.copy(fontFamily = TitanFonts.mono), color = color, modifier = modifier)
}

/** Schlichter Text in gedämpfter Farbe. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = modifier)
}

/** Ein Wert rechts in einer Zeile (`.value`). */
@Composable
internal fun Value(text: String, bold: Boolean = false) {
    Text(text, style = TitanType.rowSub.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal), color = Titan.colors.text)
}

/** Abzeichen mit Ton (`.chip ok/warn/error/info`); der Text sagt den Zustand, die Farbe nur dazu. */
@Composable
internal fun ToneChip(text: String, tone: Tone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Titan.colors
    val ink = if (tone == Tone.Inactive) c.muted else toneInk(tone)
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = TitanType.small,
        color = if (tone == Tone.Info || tone == Tone.Inactive) c.text else ink,
        modifier = modifier.clip(shape).background(ink.copy(alpha = .14f).compositeOver(c.surface))
            .border(1.dp, ink.copy(alpha = .5f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** „● an“ / „○ aus“ wie im Web. */
@Composable
internal fun OnOff(on: Boolean, onText: String, offText: String) {
    val c = Titan.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (on) c.okInk() else Color.Transparent).border(1.5.dp, if (on) c.okInk() else c.muted, CircleShape))
        Text(if (on) onText else offText, style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    }
}

private fun io.github.ra5on.titanos.ui.generated.TitanColors.okInk(): Color = if (dark) statusOk else okText

/** Kleiner Knopf nur mit Symbol (40 dp), mit Namen für Bildschirmleser. */
@Composable
internal fun IconKey(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(40.dp).clip(shape).background(c.chip).border(1.dp, c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) c.text else c.muted.copy(alpha = .5f), modifier = Modifier.size(18.dp))
    }
}

/** Knöpfe nebeneinander, die umbrechen. */
@Composable
internal fun Buttons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

/** Ein Block unter einer aufgeklappten Zeile (`.srow-block`). */
@Composable
internal fun Block(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Fortschrittsbalken mit Namen und Wert für Bildschirmleser. */
@Composable
internal fun Progress(fraction: Float, label: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 8.dp).clip(RoundedCornerShape(4.dp)).background(c.track)
            .semantics { contentDescription = label; stateDescription = "${(fraction * 100).toInt()} %" },
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).heightIn(min = 8.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
    }
}

/** Ein Farbfeld (Vorschau einer Farbe), mit Rand. */
@Composable
internal fun Swatch(color: Color, size: androidx.compose.ui.unit.Dp = 22.dp, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(5.dp)) {
    Box(Modifier.size(size).clip(shape).background(color).border(1.dp, Titan.colors.border, shape))
}

internal fun hexColor(hex: String, fallback: Color = Color.Gray): Color =
    runCatching { Color(0xFF000000 or hex.removePrefix("#").toLong(16)) }.getOrDefault(fallback)

internal val HEX = Regex("^#[0-9a-fA-F]{6}$")

/**
 * Farbwahl wie `<input type="color">`: Vorschau, Hex-Feld und Regler für
 * Rot, Grün und Blau. [onPick] bekommt „#RRGGBB“.
 */
internal fun Overlays.pickColor(title: String, current: String, ok: String, cancel: String, onPick: (String) -> Unit) = show { close ->
    var hex by remember { mutableStateOf(if (HEX.matches(current)) current.uppercase() else "#6CB6FF") }
    val value = runCatching { hex.removePrefix("#").toLong(16) }.getOrDefault(0)
    val r = ((value shr 16) and 255).toFloat()
    val g = ((value shr 8) and 255).toFloat()
    val b = (value and 255).toFloat()
    fun set(rr: Float, gg: Float, bb: Float) {
        hex = "#%02X%02X%02X".format(rr.toInt(), gg.toInt(), bb.toInt())
    }
    ConfirmDialog(
        title = title,
        text = null,
        actions = listOf(
            DialogAction(cancel) { close() },
            DialogAction(ok) { if (HEX.matches(hex)) onPick(hex.uppercase()); close() },
        ),
        onDismiss = close,
        body = {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Swatch(hexColor(hex), 44.dp, RoundedCornerShape(10.dp))
                    var text by remember(hex) { mutableStateOf(hex) }
                    Field(text, { v -> text = v; if (HEX.matches(v)) hex = v.uppercase() }, title, Modifier.widthIn(min = 120.dp), mono = true, singleLine = true)
                }
                SliderField(r, { set(it, g, b) }, 0f..255f, "R", r.toInt().toString())
                SliderField(g, { set(r, it, b) }, 0f..255f, "G", g.toInt().toString())
                SliderField(b, { set(r, g, it) }, 0f..255f, "B", b.toInt().toString())
            }
        },
    )
}

/** Ein runder Farbpunkt zum Wählen (Akzente). */
@Composable
internal fun ColorDot(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .border(if (selected) 2.dp else 0.dp, if (selected) c.text else Color.Transparent, CircleShape)
            .padding(4.dp).clip(CircleShape).background(color)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; stateDescription = if (selected) "✓" else "" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(io.github.ra5on.titanos.ui.theme.TitanIcons.check, null, tint = if (luminance(color) > .5) Color.Black else Color.White, modifier = Modifier.size(18.dp))
    }
}

internal fun luminance(c: Color): Double = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue

