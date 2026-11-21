package io.github.ra5on.titanos.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.WebIcon
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.admin.hasText
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Text eines [Msg]; Platzhalter, die selbst Texte sind, werden mit übersetzt. */
fun Context.msg(m: Msg): String = adminText(m.key, *m.params.map { (k, v) -> k to (if (v is Msg) msg(v) else v) }.toTypedArray())

@Composable
@ReadOnlyComposable
fun tx(m: Msg): String {
    LocalConfiguration.current
    return LocalContext.current.msg(m)
}

/** Text des Webs für [key] oder [fallback], wenn es den Schlüssel nicht gibt (tOr im Web für unbekannte Kennungen). */
@Composable
@ReadOnlyComposable
fun tKnown(key: String, fallback: String, vararg params: Pair<String, Any?>): String =
    if (hasText(key)) io.github.ra5on.titanos.admin.t(key, *params) else fallback

/** Was eine Seite dem Rahmen des Programms mitteilt: eigene Ebene zurück, Tastenkürzel, ungespeicherte Änderungen. */
class ToolsCtx(
    val env: AdminEnv,
    val api: ToolsApi,
) {
    /** Öffnet eine andere Seite der Werkzeuge (null: Übersicht). */
    var go: (String?) -> Unit = {}

    var up: (() -> Unit)? by mutableStateOf(null)
    var keys: ((KeyEvent) -> Boolean)? by mutableStateOf(null)
    var dirty: Boolean by mutableStateOf(false)

    /** Öffnet einen Verweis des Webs (andere Werkzeuge-Seite oder anderes Programm), wenn die App ihn hat. */
    fun open(href: String): Boolean {
        val target = appTarget(href) ?: return false
        if (target.first == "tools") go(target.second) else env.open(target.first, target.second)
        return true
    }
}

/** Die Seite führt eine Ebene zurück (Detail → Liste), solange [up] nicht null ist. */
@Composable
fun PageUp(ctx: ToolsCtx, up: (() -> Unit)?) {
    val current by rememberUpdatedState(up)
    val has = up != null
    DisposableEffect(has) {
        ctx.up = if (has) ({ current?.invoke() }) else null
        onDispose { ctx.up = null }
    }
}

/** Tastenkürzel der Seite (nur mit Strg/Befehl oder F-Tasten, damit Textfelder ihre Tasten behalten). */
@Composable
fun PageKeys(ctx: ToolsCtx, handler: (KeyEvent) -> Boolean) {
    val current by rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        ctx.keys = { e -> current(e) }
        onDispose { ctx.keys = null }
    }
}

/** Ob die Seite ungespeicherte Änderungen hat (Rückfrage beim Verlassen). */
@Composable
fun PageDirty(ctx: ToolsCtx, dirty: Boolean) {
    SideEffect { ctx.dirty = dirty }
    DisposableEffect(Unit) { onDispose { ctx.dirty = false } }
}

fun KeyEvent.down() = type == KeyEventType.KeyDown
fun KeyEvent.ctrl() = isCtrlPressed || isMetaPressed

/** Strg+[key] gedrückt. */
fun KeyEvent.ctrlKey(k: Key) = down() && ctrl() && key == k

/** F5 oder Strg+R: neu laden. */
fun KeyEvent.refresh() = down() && (key == Key.F5 || (ctrl() && key == Key.R))

// ---------------------------------------------------------------- Bausteine

/** Lange Maschinenwerte (Befehlszeilen, Protokollzeilen, Kennungen): ganz, waagrecht scrollbar statt gekürzt. */
@Composable
fun MonoLine(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Titan.colors.muted, small: Boolean = true) {
    // Die Zeile behält ihre ganze Breite; der Rahmen darum scrollt waagrecht.
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(text, style = (if (small) TitanType.small else TitanType.body).copy(fontFamily = TitanFonts.mono), color = color, softWrap = false)
    }
}

/** Mehrzeilige Ausgabe (Skriptausgabe, Fehlertext): Zeilen bleiben ganz, waagrecht scrollbar. */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().clip(shape).background(c.chip).horizontalScroll(rememberScrollState()).padding(10.dp)) {
        Text(text, style = TitanType.small.copy(fontFamily = TitanFonts.mono), color = c.text, softWrap = false)
    }
}

/** Aufklappbarer Bereich (details/summary im Web). */
@Composable
fun Disclosure(title: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val c = Titan.colors
    val state = androidx.compose.ui.res.stringResource(if (open) io.github.ra5on.titanos.admin.R.string.admin_collapse else io.github.ra5on.titanos.admin.R.string.admin_expand)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = state }
                .heightIn(min = 44.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WebIcon(if (open) "chevronDown" else "chevronRight", size = 16.dp)
            Text(title, style = TitanType.small, color = c.muted)
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** Problemtext mit dem Original des NAS als Detail (BackendDetail im Web). */
@Composable
fun ProblemText(p: Problem, color: androidx.compose.ui.graphics.Color = Titan.colors.text) {
    Column {
        Text(tx(p.text), style = TitanType.body, color = color)
        if (p.detail.isNotEmpty()) Disclosure(io.github.ra5on.titanos.admin.t("ops.details")) { MonoBlock(p.detail) }
    }
}

/** Zahlen der Seite in einer Leiste (StatusBar im Web). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusLine(items: List<Pair<String, Boolean>>, modifier: Modifier = Modifier) {
    val c = Titan.colors
    FlowRow(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.header).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((text, warn) in items) {
            Text(text, style = TitanType.small.copy(fontWeight = if (warn) FontWeight.SemiBold else FontWeight.Normal), color = if (warn) c.warnText else c.muted)
        }
    }
}

/** Überschrift eines Abschnitts innerhalb einer Seite. */
@Composable
fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.rowTitle, color = Titan.colors.text, modifier = modifier.padding(top = 4.dp).semantics { heading() })
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.small, color = Titan.colors.muted, modifier = modifier)
}

// ---------------------------------------------------------------- Speichern über den Speicherdialog

/** Speicherdialog von Android (Downloads oder eigener Ort) mit Dateiname und Typ. */
private class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Stand einer Datei, die gerade gespeichert wird. */
class SaveState {
    var busy by mutableStateOf(false)
    var bytes by mutableLongStateOf(0L)
    var total by mutableLongStateOf(-1L)
    var done by mutableStateOf<String?>(null)
    var error by mutableStateOf<Throwable?>(null)
}

/**
 * Speichert eine Datei vom NAS: erst wählt man im Speicherdialog Ort und Namen,
 * dann schreibt [write] (z. B. ToolsApi.download) in die Datei.
 */
class Saver(val state: SaveState, private val start: (String, String, suspend (OutputStream, (Long, Long) -> Unit) -> Unit) -> Unit) {
    fun save(name: String, mime: String, write: suspend (OutputStream, (Long, Long) -> Unit) -> Unit) = start(name, mime, write)
}

@Composable
fun rememberSaver(): Saver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { SaveState() }
    val pending: MutableState<Pair<String, suspend (OutputStream, (Long, Long) -> Unit) -> Unit>?> = remember { mutableStateOf(null) }
    val launcher = rememberLauncherForActivityResult(CreateFile()) { uri ->
        val job = pending.value
        pending.value = null
        if (uri == null || job == null) return@rememberLauncherForActivityResult
        state.busy = true
        state.bytes = 0
        state.total = -1
        state.error = null
        state.done = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        job.second(out) { done, total ->
                            state.bytes = done
                            state.total = total
                        }
                    }
                }
                state.done = job.first
            } catch (e: Exception) {
                state.error = e
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            } finally {
                state.busy = false
            }
        }
    }
    return remember(launcher) {
        Saver(state) { name, mime, write ->
            pending.value = name to write
            runCatching { launcher.launch(name to mime) }.onFailure { state.error = it }
        }
    }
}

/** Formatierer für Diagramme (außerhalb der Komposition aufrufbar). */
@Composable
fun percentFormat(): (Double) -> String {
    val locale = io.github.ra5on.titanos.admin.uiLocale()
    return { io.github.ra5on.titanos.admin.AdminFormat.percent(it, locale) }
}

@Composable
fun numberFormat(unit: String, digits: Int = 2): (Double) -> String {
    val locale = io.github.ra5on.titanos.admin.uiLocale()
    return { "${io.github.ra5on.titanos.admin.AdminFormat.number(it, digits, locale)} $unit".trimEnd() }
}

/** Lange Wortverbindungen mit Bindestrich („Benachrichtigungs-Einstellungen“) dürfen nach dem Bindestrich umbrechen, nie mitten im Wort. */
fun breakAfterHyphens(text: String): String = text.replace(Regex("(?<=\\p{L})-(?=\\p{L})"), "-\u200B")
