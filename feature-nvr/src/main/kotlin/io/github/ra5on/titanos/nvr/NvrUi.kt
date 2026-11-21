package io.github.ra5on.titanos.nvr

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.hasText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.tOr
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale

// Gemeinsame Bausteine der NVR-Seiten im Stil von core-ui: Gruppen mit
// Zeilen wie SettingGroup/SettingRow des Webs, Pillen, Abschnitte. Text
// bricht immer um; Steuerelemente stehen unter dem Text, wenn es eng wird.

@Composable
@ReadOnlyComposable
fun locale(): Locale = LocalConfiguration.current.locales[0]

/** Eine Gruppe wie SettingGroup: Überschrift, Hinweis, Karte mit Zeilen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Group(title: String, desc: String? = null, tools: (@Composable FlowRowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, Modifier.semantics { heading() })
        if (desc != null) Text(desc, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
        if (tools != null) FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = tools)
        Card(content = content)
    }
}

/**
 * Eine Zeile wie SettingRow: Symbol, Titel, Beschreibung, darunter (oder
 * rechts bei breitem Fenster) die Steuerung. [divider] trennt von der Zeile davor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Row2(
    title: String,
    icon: ImageVector? = null,
    desc: String? = null,
    divider: Boolean = false,
    summary: String? = null,
    descContent: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    control: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth()) {
        if (divider) Divider()
        Column(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) Icon(icon, null, tint = c.muted, modifier = Modifier.padding(top = 1.dp).size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = TitanType.rowTitle, color = c.text)
                    if (summary != null) Text(summary, style = TitanType.rowSub, color = c.text)
                    if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted)
                    descContent?.invoke(this)
                }
                if (onClick != null) Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
            }
            if (control != null) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = if (icon != null) 32.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    content = control,
                )
            }
        }
    }
}

/** Beschreibungstext in einer Zeile. */
@Composable
fun Sub(text: String, warn: Boolean = false, danger: Boolean = false, mono: Boolean = false) {
    val c = Titan.colors
    Text(
        text,
        style = if (mono) TitanType.rowSub.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.rowSub,
        color = if (danger) c.dangerInk else if (warn) c.warnInk else c.muted,
    )
}

data class Pill<T>(val value: T, val label: String, val enabled: Boolean = true, val why: String? = null)

/** Mehrfachauswahl als Pillen, die umbrechen (Pills.svelte); eine gesperrte sagt, warum. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Pills(options: List<Pill<T>>, chosen: Collection<T>, label: String, onToggle: (T, Boolean) -> Unit, enabled: Boolean = true) {
    val c = Titan.colors
    val onWord = stringResource(R.string.nvr_chosen)
    val offWord = stringResource(R.string.nvr_not_chosen)
    Column(Modifier.semantics { contentDescription = label }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in options) {
                val on = o.value in chosen
                val can = enabled && o.enabled
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier.heightIn(min = 40.dp).clip(shape).background(if (on) c.accent.copy(alpha = .2f) else c.chip)
                        .border(1.dp, if (on) c.accent else c.border, shape)
                        .clickable(enabled = can, role = Role.Checkbox) { onToggle(o.value, !on) }
                        .semantics {
                            toggleableState = ToggleableState(on)
                            stateDescription = if (on) onWord else offWord
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (on) Icon(TitanIcons.check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Text(o.label, style = TitanType.body, color = if (can) c.text else c.muted)
                }
            }
        }
        for (o in options) if (!o.enabled && o.why != null) Sub(o.why)
    }
}

/** Eine Wahl aus wenigen Werten als Abschnitte (Segments.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, label: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    val c = Titan.colors
    FlowRow(
        Modifier.semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((key, text) in options) {
            val on = key == selected
            val shape = RoundedCornerShape(10.dp)
            Text(
                text,
                style = TitanType.body,
                color = if (on) c.onAccent else if (enabled) c.text else c.muted,
                modifier = Modifier.heightIn(min = 40.dp).clip(shape).background(if (on) c.accent else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(key) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** Ein Zahlenfeld mit Einheit; leer bleibt leer, bis eine Zahl dasteht. */
@Composable
fun NumberField(label: String, value: Int, onChange: (Int) -> Unit, unit: String? = null, range: IntRange = 0..100000) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.widthIn(min = 96.dp, max = 140.dp)) {
            TextInput(text, { s ->
                text = s.filter { it.isDigit() }.take(6)
                text.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
            }, label, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (unit != null) Text(unit, style = TitanType.body, color = Titan.colors.text)
    }
}

/** Eine Fehlermeldung in Worten (nie nur Farbe). */
@Composable
fun ErrorLine(e: Throwable?) {
    if (e != null) io.github.ra5on.titanos.ui.components.Problem(errorText(e))
}

// ---- Namen und Sätze (texts.ts) ----

@Composable
fun labelName(label: String): String = tOr("nvr.object.$label", label)

@Composable
fun deviceName(kind: String, devices: List<NvrDevice> = emptyList()): String {
    val name = tOr("nvr.device.$kind", kind)
    val found = devices.firstOrNull { it.kind == kind }
    return if (found != null && found.title.isNotEmpty() && found.title != name) "$name (${found.title})" else name
}

@Composable
fun deviceReason(reason: String?): String = t("nvr.device.reason.${if (reason in setOf("driver", "vm", "plugin")) reason else "other"}")

@Composable
fun reasonTitle(code: String) = t("nvr.reason.${reasonKey(code)}.title")

@Composable
fun reasonMeaning(code: String) = t("nvr.reason.${reasonKey(code)}.what")

@Composable
fun reasonAdvice(code: String) = t("nvr.reason.${reasonKey(code)}.todo")

@Composable
fun decoderName(id: String, decoders: List<NvrDecoder>): String {
    val d = decoders.firstOrNull { it.id == id } ?: return if (id == "cpu") t("nvr.decoder.cpu") else id
    val title = d.title.ifEmpty { d.id }
    return if (d.kind == "igpu") "${t("nvr.decoder.kind.igpu")} ($title)" else title
}

@Composable
fun decoderReason(reason: String) = t("nvr.decoder.reason.${if (reason in setOf("gone", "vm", "driver")) reason else "driver"}")

@Composable
fun stateText(camera: Camera, running: Boolean): String {
    val cond = camera.condition(running)
    if (cond == Condition.Offline && camera.state.offlineSince != null) return t("nvr.camera.offlineSince", "time" to sinceText(camera.state.offlineSince, locale()))
    return t("nvr.camera.state.${cond.name.lowercase()}")
}

fun Context.tx(key: String, vararg params: Pair<String, Any?>): String = webText(key, *params)

fun keyOr(key: String, fallback: String): String = if (hasText(key)) key else fallback

// ---- Herunterladen über das Speicher-Framework ----

/**
 * Speichert eine Datei vom NAS dort, wo man sie hinlegt (SAF „Speichern
 * unter“). DownloadManager kennt weder das gepinnte Zertifikat noch die
 * Sitzung; deshalb geht der Strom über die Verbindung der App.
 */
class Saver(private val start: (String, String, String) -> Unit) {
    fun save(path: String, name: String, mime: String) = start(path, name, mime)
}

@Composable
fun rememberSaver(access: ComputeAccess?): Saver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val done = stringResource(R.string.nvr_saved)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val job = pending
        pending = null
        if (uri == null || job == null || access == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    access.execute { base -> Request.Builder().url(access.url(base, job.first)).build() }.use { r ->
                        r.throwIfError()
                        context.contentResolver.openOutputStream(uri)?.use { out -> r.body.byteStream().copyTo(out) }
                    }
                }
                android.widget.Toast.makeText(context, done, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, context.errorText(e, detail = true), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    return remember(launcher) {
        Saver { path, name, _ ->
            pending = path to name
            launcher.launch(name)
        }
    }
}
