package io.github.ra5on.titanos.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.ErrorBox
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
private data class RunningContainer(val id: String = "", val name: String = "", val state: String = "", val app: String = "", val service: String = "")

@Serializable
private data class DockerList(val containers: List<RunningContainer> = emptyList())

@Serializable
private data class Machine(val name: String = "", val displayName: String = "", val state: String = "")

@Serializable
private data class MachineList(val vms: List<Machine> = emptyList())

@Serializable
private data class LxcItem(val name: String = "", val state: String = "", val description: String = "")

@Serializable
private data class LxcList(val containers: List<LxcItem> = emptyList())

/** Einstellungen dieses Handys für das Terminal. */
internal object TermPrefs {
    private const val FILE = "terminal"

    fun fontSize(context: Context): Float = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("fontSize", 13f).coerceIn(MIN_FONT, MAX_FONT)

    fun setFontSize(context: Context, size: Float) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat("fontSize", size).apply()

    fun keyBar(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("keyBar", true)

    fun setKeyBar(context: Context, on: Boolean) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("keyBar", on).apply()

    /** Mausmeldungen an Programme (Tippen, Wischen, Maus); aus: Wischen blättert immer im Verlauf. */
    fun mouse(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("mouse", true)

    fun setMouse(context: Context, on: Boolean) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("mouse", on).apply()

    fun fnRow(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("fnRow", false)

    fun setFnRow(context: Context, on: Boolean) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("fnRow", on).apply()
}

/**
 * Terminal wie TerminalPanel.svelte: Reiter für NAS-Shell und Konsolen,
 * „+“ für eine neue Sitzung, Kopieren/Einfügen, Suchen, Schriftgröße und die
 * Sondertastenleiste. Keine zweite Passwortabfrage; das NAS trennt nach der
 * eingestellten Zeit ohne Eingabe.
 */
@Composable
fun TerminalRoot(env: ComputeEnv, onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<TerminalInfo?>(null) }
    var infoError by remember { mutableStateOf<Throwable?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        try {
            info = env.access.get<TerminalInfo>("/terminal")
            infoError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            infoError = e
        }
    }
    // Eine erste Sitzung, damit das Terminal nicht leer ist – nicht, nachdem die letzte geschlossen wurde.
    LaunchedEffect(info) {
        val i = info ?: return@LaunchedEffect
        if (i.enabled && i.nasShell && Consoles.sessions.isEmpty() && !Consoles.lastClosed) Consoles.open(ConsoleRequest.nas())
    }
    val newLabel = t("terminal.new")
    val settingsLabel = t("settings.consoles")
    ProgramShell(
        title = t("terminal.title"),
        glyph = "terminal",
        gradient = "dark",
        sections = emptyList(),
        selected = "",
        onSelect = {},
        onBack = onBack,
        actions = {
            val ui = LocalComputeUi.current
            IconButton(TitanIcons.plus, newLabel, { openNewSessionMenu(env, info, ui, context) })
            if (env.admin) IconButton(TitanIcons.gear, settingsLabel, { ui.sheet = { TerminalSettingsSheet(env, info) { info = it } } })
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            when {
                infoError != null && info == null -> Box(Modifier.padding(16.dp)) { ErrorBox(errorText(infoError!!), { reload++ }) }
                info != null && info?.enabled == false -> Box(Modifier.padding(16.dp)) { Problem(t("terminal.off")) }
                else -> Sessions(env, info)
            }
        }
    }
}

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.Sessions(env: ComputeEnv, info: TerminalInfo?) {
    val context = LocalContext.current
    val ui = LocalComputeUi.current
    val scope = rememberCoroutineScope()
    val sessions = Consoles.sessions
    if (sessions.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyState(t("terminal.sessions"), t(if (info?.nasShell == false) "terminal.emptyNoShell" else "terminal.empty"))
            if (info?.nasShell != false) TitanButton(t("terminal.newNas"), { Consoles.open(ConsoleRequest.nas()) }, icon = TitanIcons.plus, kind = ButtonKind.Primary)
            TitanButton(t("terminal.newContainer"), { openNewSessionMenu(env, info, ui, context) }, icon = TitanIcons.docker)
        }
        return
    }
    val active = sessions.firstOrNull { it.id == Consoles.active } ?: sessions.last()
    LaunchedEffect(active.id) {
        if (active.phase == Phase.Connecting) active.connect(env.access, context, Consoles.scope)
    }
    var fontSize by remember { mutableFloatStateOf(TermPrefs.fontSize(context)) }
    var keyBar by remember { mutableStateOf(TermPrefs.keyBar(context)) }
    var fnRow by remember { mutableStateOf(TermPrefs.fnRow(context)) }
    var mouse by remember { mutableStateOf(TermPrefs.mouse(context)) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val viewState = remember(active.id) { TermViewState() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val sink = remember(active.id) {
        object : TermInputSink {
            override fun typed(text: String) {
                viewState.scroll = 0
                active.send(TermKeys.text(text, ctrl, alt))
                ctrl = false
                alt = false
            }

            override fun key(key: TermKey) {
                viewState.scroll = 0
                active.send(TermKeys.bytes(key, active.emulator.appCursor, alt = alt, ctrl = ctrl))
                ctrl = false
                alt = false
            }

            override fun keyEvent(event: KeyEvent): Boolean {
                // Strg+Umschalt+C/V wie im Web: kopieren und einfügen.
                if (event.isCtrlPressed && event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_C) {
                    copySelection(context, active, viewState)
                    return true
                }
                if (event.isCtrlPressed && event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_V) {
                    paste(context, active)
                    return true
                }
                if (event.isCtrlPressed && (event.keyCode == KeyEvent.KEYCODE_PLUS || event.keyCode == KeyEvent.KEYCODE_EQUALS)) {
                    fontSize = (fontSize + 1).coerceAtMost(MAX_FONT); TermPrefs.setFontSize(context, fontSize); return true
                }
                if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_MINUS) {
                    fontSize = (fontSize - 1).coerceAtLeast(MIN_FONT); TermPrefs.setFontSize(context, fontSize); return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || event.keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_CTRL_LEFT || event.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_ALT_LEFT || event.keyCode == KeyEvent.KEYCODE_ALT_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_BACK
                ) return false
                val bytes = hardwareKeyBytes(event, active.emulator.appCursor, ctrl, alt) ?: return false
                viewState.scroll = 0
                active.send(bytes)
                ctrl = false
                alt = false
                return true
            }
        }
    }
    SessionTabs(sessions, active, ui)
    Status(active, info, env)
    if (searching) {
        SearchBar(query, { query = it }, onFind = { back ->
            synchronized(active.emulator) {
                val emu = active.emulator
                val from = emu.historySize - viewState.scroll
                val hit = emu.find(query, if (back) from else from + emu.rows - 1, backwards = back)
                if (hit >= 0) viewState.scroll = (emu.historySize - hit).coerceIn(0, emu.historySize)
            }
        }, onClose = { searching = false })
    }
    val screen: @Composable () -> Unit = { Box(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        TermView(
            active,
            fontSize,
            viewState,
            sink,
            onFontSize = { fontSize = it; TermPrefs.setFontSize(context, it) },
            label = t("terminal.title") + " " + active.title,
            mouseReports = mouse,
        )
        if (active.phase == Phase.Ended) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Titan.colors.surface).border(1.dp, Titan.colors.border, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(active.reason ?: stringResource(R.string.term_ended), style = TitanType.body, color = Titan.colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TitanButton(stringResource(R.string.term_reconnect), { active.reconnect(env.access, context, Consoles.scope) }, icon = TitanIcons.restart, kind = ButtonKind.Primary)
                    TitanButton(stringResource(R.string.term_close), { Consoles.close(active.id) }, icon = TitanIcons.x)
                }
            }
        }
    } }
    val controls: @Composable () -> Unit = { Toolbar(
        fontSize = fontSize,
        onFont = { fontSize = it.coerceIn(MIN_FONT, MAX_FONT); TermPrefs.setFontSize(context, fontSize) },
        onCopy = { copySelection(context, active, viewState) },
        onCopyAll = { copyText(context, synchronized(active.emulator) { active.emulator.allText() }) },
        onPaste = { paste(context, active) },
        onSearch = { searching = !searching },
        onClear = { active.clear() },
        onKeyboard = { viewState.showKeyboard() },
        keyBar = keyBar,
        onKeyBar = { keyBar = it; TermPrefs.setKeyBar(context, it) },
        fnRow = fnRow,
        onFnRow = { fnRow = it; TermPrefs.setFnRow(context, it) },
        mouse = mouse,
        onMouse = { mouse = it; TermPrefs.setMouse(context, it) },
        hasSelection = viewState.selection != null,
    )
    if (keyBar) KeyBar(ctrl, alt, fnRow, onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt }, onKey = { sink.key(it) }, onText = { sink.typed(it) }) }
    // Faltbares in Tischhaltung: oben die Konsole, unten Leiste und Tasten (core-ui FoldPanes);
    // die Sitzung bleibt beim Auf- und Zuklappen bestehen.
    io.github.ra5on.titanos.ui.FoldPanes(
        first = screen,
        second = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { controls() } },
        modifier = Modifier.weight(1f).fillMaxWidth(),
        book = false,
    ) { a, _ ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { a() }
            controls()
        }
    }
}

@Composable
private fun SessionTabs(sessions: List<ConsoleSession>, active: ConsoleSession, ui: io.github.ra5on.titanos.compute.ComputeUi) {
    val c = Titan.colors
    val endedWord = stringResource(R.string.term_state_ended)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in sessions) {
            val on = s.id == active.id
            val shape = RoundedCornerShape(12.dp)
            val endLabel = t("terminal.end", "name" to s.title)
            val connected = s.phase == Phase.Open
            val menuEntries = listOf(
                MenuEntry(endLabel, TitanIcons.x, danger = true) { askClose(ui, s, endLabel) },
            )
            Row(
                Modifier.clip(shape).background(if (on) c.accent.copy(alpha = .2f) else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
                    .onSecondaryClick { ui.menu(s.title, menuEntries) }
                    .clickable(role = Role.Tab) { Consoles.active = s.id }
                    .semantics {
                        selected = on
                        if (s.phase == Phase.Ended) stateDescription = endedWord
                    }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(if (s.request.kind == "ct") TitanIcons.docker else if (s.request.kind == "vm") TitanIcons.monitor else if (s.request.kind == "lxc") TitanIcons.box else TitanIcons.hdd, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(s.title + if (!connected && s.phase == Phase.Ended) " · $endedWord" else "", style = TitanType.body, color = c.text, modifier = Modifier.widthIn(max = 260.dp).padding(vertical = 8.dp))
                IconButton(TitanIcons.x, endLabel, { askClose(ui, s, endLabel) }, size = 16.dp)
            }
        }
    }
}

private fun askClose(ui: io.github.ra5on.titanos.compute.ComputeUi, s: ConsoleSession, label: String) {
    // Eine verbundene NAS-Shell wie im Web erst nach Rückfrage beenden.
    if (s.request.kind == "nas" && s.phase == Phase.Open) {
        ui.confirm(
            Ask(
                title = ui.text("terminal.endTitle"),
                text = ui.text("terminal.endText", "name" to s.title),
                confirm = ui.text("terminal.endConfirm"),
            ) { Consoles.close(s.id) },
        )
    } else {
        Consoles.close(s.id)
    }
}

@Composable
private fun Status(s: ConsoleSession, info: TerminalInfo?, env: ComputeEnv) {
    val time = remember(s.started) { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(s.started)) }
    val line = when (s.request.kind) {
        "nas" -> t("terminal.statusNas", "time" to time)
        "vm" -> t("terminal.statusVm", "name" to s.title, "time" to time)
        "lxc" -> t("terminal.statusLxc", "name" to s.title, "time" to time)
        else -> t("terminal.statusContainer", "name" to s.title, "time" to time)
    }
    val idle = info?.idleMinutes ?: 15
    val footer = if (idle == 0) stringResource(R.string.term_footer_never, env.user.ifEmpty { "–" }) else stringResource(R.string.term_footer, env.user.ifEmpty { "–" }, idle)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (s.phase) {
                Phase.Connecting -> StateMark(stringResource(R.string.term_state_connecting), StateKind.Busy)
                Phase.Open -> StateMark(stringResource(R.string.term_state_open), StateKind.Ok)
                Phase.Ended -> StateMark(stringResource(R.string.term_state_ended), StateKind.Off)
            }
            Text(line, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.weight(1f))
        }
        Text(footer, style = TitanType.small, color = Titan.colors.muted)
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onFind: (Boolean) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Field(t("terminal.search"), query, onQuery) }
        IconButton(TitanIcons.back, stringResource(R.string.term_find_prev), { onFind(true) })
        IconButton(TitanIcons.chev, stringResource(R.string.term_find_next), { onFind(false) })
        IconButton(TitanIcons.x, stringResource(R.string.term_close), onClose)
    }
}

@Composable
private fun Toolbar(
    fontSize: Float,
    onFont: (Float) -> Unit,
    onCopy: () -> Unit,
    onCopyAll: () -> Unit,
    onPaste: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onKeyboard: () -> Unit,
    keyBar: Boolean,
    onKeyBar: (Boolean) -> Unit,
    fnRow: Boolean,
    onFnRow: (Boolean) -> Unit,
    mouse: Boolean,
    onMouse: (Boolean) -> Unit,
    hasSelection: Boolean,
) {
    val ui = LocalComputeUi.current
    val c = Titan.colors
    val more = stringResource(R.string.term_more)
    val copyAll = stringResource(R.string.term_copy_all)
    val clear = stringResource(R.string.term_clear)
    val keyBarLabel = stringResource(if (keyBar) R.string.term_keybar_hide else R.string.term_keybar_show)
    val mouseLabel = stringResource(if (mouse) R.string.term_mouse_off else R.string.term_mouse_on)
    val fnLabel = stringResource(if (fnRow) R.string.term_fn_hide else R.string.term_fn_show)
    val smaller = t("terminal.fontSmaller")
    val larger = t("terminal.fontLarger")
    Row(
        Modifier.fillMaxWidth().background(c.header).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(TitanIcons.edit, stringResource(R.string.term_keyboard), onKeyboard)
        IconButton(TitanIcons.file, t("terminal.copy"), onCopy, tint = if (hasSelection) c.accent else c.text)
        IconButton(TitanIcons.download, t("terminal.paste"), onPaste)
        IconButton(TitanIcons.search, t("terminal.search"), onSearch)
        Text("A−", style = TitanType.button, color = c.text, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onFont(fontSize - 1) }
            .semantics { contentDescription = smaller }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 11.dp))
        Text(fontSize.toInt().toString(), style = TitanType.body, color = c.muted, modifier = Modifier.semantics { contentDescription = fontSize.toInt().toString() })
        Text("A+", style = TitanType.button, color = c.text, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onFont(fontSize + 1) }
            .semantics { contentDescription = larger }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 11.dp))
        IconButton(TitanIcons.more, more, {
            ui.menu(
                more,
                listOf(
                    MenuEntry(copyAll, TitanIcons.file) { onCopyAll() },
                    MenuEntry(clear, TitanIcons.trash) { onClear() },
                    MenuEntry(keyBarLabel, TitanIcons.grid) { onKeyBar(!keyBar) },
                    MenuEntry(fnLabel, TitanIcons.grid) { onFnRow(!fnRow) },
                    MenuEntry(mouseLabel, TitanIcons.grid) { onMouse(!mouse) },
                ),
            )
        })
    }
}

/** Sondertastenleiste: Esc, Tab, Strg, Alt, Pfeile und Zeichen, die Handy-Tastaturen verstecken. */
@Composable
private fun KeyBar(ctrl: Boolean, alt: Boolean, fnRow: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit, onKey: (TermKey) -> Unit, onText: (String) -> Unit) {
    val c = Titan.colors
    val held = stringResource(R.string.term_held)
    val notHeld = stringResource(R.string.term_not_held)
    @Composable
    fun key(label: String, description: String, on: Boolean? = null, onClick: () -> Unit) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(shape)
                .background(if (on == true) c.accent else c.chip).border(1.dp, if (on == true) c.accent else c.border, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = description
                    if (on != null) stateDescription = if (on) held else notHeld
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TitanType.body.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = if (on == true) c.onAccent else c.text)
        }
    }
    Column(Modifier.fillMaxWidth().background(c.header).padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            key("Esc", "Escape") { onKey(TermKey.Esc) }
            key("Tab", "Tab") { onKey(TermKey.Tab) }
            key(stringResource(R.string.term_ctrl_short), stringResource(R.string.term_ctrl), ctrl, onCtrl)
            key("Alt", "Alt", alt, onAlt)
            key("←", stringResource(R.string.term_left)) { onKey(TermKey.Left) }
            key("↑", stringResource(R.string.term_up)) { onKey(TermKey.Up) }
            key("↓", stringResource(R.string.term_down)) { onKey(TermKey.Down) }
            key("→", stringResource(R.string.term_right)) { onKey(TermKey.Right) }
            key("Pos1", stringResource(R.string.term_home)) { onKey(TermKey.Home) }
            key(stringResource(R.string.term_end_short), stringResource(R.string.term_end_key)) { onKey(TermKey.End) }
            key("Bild↑", stringResource(R.string.term_pgup)) { onKey(TermKey.PageUp) }
            key("Bild↓", stringResource(R.string.term_pgdn)) { onKey(TermKey.PageDown) }
            for (ch in listOf("|", "~", "/", "-", "_", "\\", ":", ";", "'", "\"", "`", "$", "&", "*", "<", ">", "{", "}", "[", "]")) key(ch, ch) { onText(ch) }
            key("Entf", stringResource(R.string.term_delete)) { onKey(TermKey.Delete) }
        }
        if (fnRow) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val fkeys = listOf(TermKey.F1, TermKey.F2, TermKey.F3, TermKey.F4, TermKey.F5, TermKey.F6, TermKey.F7, TermKey.F8, TermKey.F9, TermKey.F10, TermKey.F11, TermKey.F12)
                for ((i, k) in fkeys.withIndex()) key("F${i + 1}", "F${i + 1}") { onKey(k) }
                key("Einfg", stringResource(R.string.term_insert)) { onKey(TermKey.Insert) }
                key("⇤", stringResource(R.string.term_backtab)) { onKey(TermKey.BackTab) }
            }
        }
    }
}

private fun openNewSessionMenu(env: ComputeEnv, info: TerminalInfo?, ui: io.github.ra5on.titanos.compute.ComputeUi, context: Context) {
    val title = context.webText("terminal.new")
    ui.menu(title, listOf(MenuEntry(context.getString(R.string.term_loading), enabled = false) {}))
    ui.run {
        val entries = mutableListOf<MenuEntry>()
        if (info?.nasShell != false) entries += MenuEntry(context.webText("terminal.newNas"), TitanIcons.hdd) { Consoles.open(ConsoleRequest.nas()) }
        val containers = runCatching { env.access.get<DockerList>("/docker").containers.filter { it.state == "running" } }.getOrDefault(emptyList())
        if (containers.isEmpty()) entries += MenuEntry(context.webText("terminal.noContainers"), TitanIcons.docker, enabled = false) {}
        for (ct in containers) {
            val name = ct.name.removePrefix("/")
            entries += MenuEntry(context.getString(R.string.term_container_entry, name), TitanIcons.docker) {
                ui.sheet = { ContainerOptions(ct.id, name, ui) }
            }
        }
        val vms = runCatching { env.access.get<MachineList>("/vms").vms.filter { it.state == "running" } }.getOrDefault(emptyList())
        for (vm in vms) {
            val label = vm.displayName.ifEmpty { vm.name }
            entries += MenuEntry(context.getString(R.string.term_vm_entry, label), TitanIcons.monitor) { Consoles.open(ConsoleRequest.vm(vm.name, label)) }
        }
        val lxc = runCatching { env.access.get<LxcList>("/lxc").containers.filter { it.state.equals("running", true) } }.getOrDefault(emptyList())
        for (l in lxc) {
            entries += MenuEntry(context.getString(R.string.term_lxc_entry, l.name), TitanIcons.box) { Consoles.open(ConsoleRequest.lxc(l.name)) }
            entries += MenuEntry(context.getString(R.string.term_lxc_tty_entry, l.name), TitanIcons.box) { Consoles.open(ConsoleRequest.lxc(l.name, textConsole = true)) }
        }
        if (ui.menu != null) ui.menu(title, entries)
    }
}

@Composable
private fun ContainerOptions(id: String, name: String, ui: io.github.ra5on.titanos.compute.ComputeUi) {
    var shell by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("terminal.newContainer") + ": " + name, style = TitanType.rowTitle, color = Titan.colors.text)
        Choice(t("terminal.shell"), listOf("" to t("terminal.shellAuto"), "bash" to "bash", "sh" to "sh"), shell, { shell = it })
        Field(t("terminal.user"), user, { user = it }, placeholder = t("terminal.userDefault"), hint = stringResource(R.string.term_user_hint))
        TitanButton(stringResource(R.string.term_open), {
            ui.sheet = null
            Consoles.open(ConsoleRequest.container(id, name, shell, user.trim()))
        }, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TerminalSettingsSheet(env: ComputeEnv, info: TerminalInfo?, onInfo: (TerminalInfo) -> Unit) {
    val ui = LocalComputeUi.current
    var current by remember { mutableStateOf(info) }
    val minutes = listOf(5, 15, 30, 60, 120)
    Column(Modifier.fillMaxWidth()) {
        Text(t("settings.consoles"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(bottom = 8.dp))
        val i = current
        if (i == null) {
            Problem(stringResource(R.string.term_offline))
            return@Column
        }
        SwitchRow(t("settings.terminal"), i.enabled, { on ->
            ui.run {
                val next = env.access.call<kotlinx.serialization.json.JsonObject, TerminalInfo>("PUT", "/terminal", buildJsonObject { put("enabled", JsonPrimitive(on)) })
                current = next
                onInfo(next)
            }
        }, sub = t("settings.terminalDesc"))
        Choice(
            t("settings.consoleIdle"),
            listOf("0" to t("settings.consoleIdleNever")) + minutes.map { it.toString() to t("settings.consoleIdleMinutes", "minutes" to it) },
            i.idleMinutes.toString(),
            { v ->
                ui.run {
                    val next = env.access.call<kotlinx.serialization.json.JsonObject, TerminalInfo>("PUT", "/terminal", buildJsonObject { put("idleMinutes", JsonPrimitive(v.toInt())) })
                    current = next
                    onInfo(next)
                }
            },
            hint = t("settings.consoleIdleDesc"),
        )
        Note(stringResource(R.string.term_no_password))
    }
}

private fun copySelection(context: Context, s: ConsoleSession, state: TermViewState) {
    val sel = state.selection?.ordered ?: return
    val text = synchronized(s.emulator) { s.emulator.text(sel.startLine, sel.startCol, sel.endLine, sel.endCol) }
    copyText(context, text)
    state.selection = null
}

private fun copyText(context: Context, text: String) {
    if (text.isEmpty()) return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
}

private fun paste(context: Context, s: ConsoleSession) {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
    if (clip.itemCount == 0) return
    val text = clip.getItemAt(0).coerceToText(context).toString()
    if (text.isNotEmpty()) s.send(TermKeys.paste(text, s.emulator.bracketedPaste))
}
