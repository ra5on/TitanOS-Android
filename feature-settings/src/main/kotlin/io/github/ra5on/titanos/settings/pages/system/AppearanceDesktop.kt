package io.github.ra5on.titanos.settings.pages.system

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.boolOrNull
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---- Hintergründe des Arbeitsplatzes (web/src/lib/desktop/wallpapers.ts, prefs.ts) ----

internal val WALLPAPERS = listOf("wave", "aurora", "dunes", "peaks", "grid", "calm", "nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")
private val PHOTOS = setOf("nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")

// base, deep, mid, glow (dunkel | hell), wie die gezeichneten Hintergründe des Webs.
private val WALL_COLORS: Map<String, Pair<List<Long>, List<Long>>> = mapOf(
    "wave" to (listOf(0xFF060B18, 0xFF0A1530, 0xFF16336B, 0xFF2A5BB0) to listOf(0xFFE9F1FF, 0xFFCFE1FF, 0xFF8FB8F5, 0xFF5C8FE0)),
    "aurora" to (listOf(0xFF04100F, 0xFF06231F, 0xFF0D5A4F, 0xFF2BB39A) to listOf(0xFFEAFAF6, 0xFFC9F1E7, 0xFF86DCC8, 0xFF3FB39C)),
    "dunes" to (listOf(0xFF140B06, 0xFF2A160B, 0xFF6B3A16, 0xFFC97A2F) to listOf(0xFFFFF5EA, 0xFFFFE3C4, 0xFFF6BD83, 0xFFE08A3C)),
    "peaks" to (listOf(0xFF0B0716, 0xFF1A1035, 0xFF3B2A7A, 0xFF7B5CD6) to listOf(0xFFF3EFFF, 0xFFDDD3FF, 0xFFB5A3F5, 0xFF8566E0)),
    "grid" to (listOf(0xFF07090C, 0xFF10151C, 0xFF1C2632, 0xFF2F6FB0) to listOf(0xFFF4F6F9, 0xFFE4E9F0, 0xFFC8D3E0, 0xFF6C97C8)),
    "calm" to (listOf(0xFF0B0D10, 0xFF14181D, 0xFF20262E, 0xFF3A4654) to listOf(0xFFF6F7F9, 0xFFECEFF3, 0xFFD9DEE5, 0xFFAAB4C0)),
)
private val PHOTO_STAND_IN = mapOf("nordlicht" to "aurora", "bergsee" to "peaks", "kueste" to "wave", "nebelwald" to "calm", "wueste" to "dunes", "weltraum" to "peaks")

/** Der gewählte Hintergrund: ("builtin", id) oder ("custom", version). */
internal data class Wall(val custom: Boolean, val id: String, val version: Long, val live: Boolean)

internal fun wallpaperOf(desktop: JsonObject?): Wall {
    val wp = desktop.o("wallpaper")
    val live = wp.boolOrNull("live") != false
    return if (wp.str("source") == "custom") Wall(true, "", wp.long("version"), live)
    else Wall(false, wp.str("id").takeIf { it in WALLPAPERS } ?: "wave", 0, live)
}

/** Ein Verlauf in den Farben eines Hintergrunds (Ersatz, solange ein Foto lädt). */
internal fun wallpaperBrush(w: Wall, light: Boolean): Brush {
    val id = if (w.custom) "wave" else PHOTO_STAND_IN[w.id] ?: w.id
    val (dark, bright) = WALL_COLORS[id] ?: WALL_COLORS.getValue("wave")
    val c = if (light) bright else dark
    return Brush.linearGradient(listOf(Color(c[0]), Color(c[1]), Color(c[2]), Color(c[3])))
}

/** Ein Hintergrund als Fläche: Foto oder eigenes Bild vom NAS, sonst sein Verlauf. */
@Composable
internal fun WallpaperFill(w: Wall, light: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val env = LocalEnv.current
    val path = when {
        w.custom -> "/api/v1/background?v=${w.version}"
        w.id in PHOTOS -> "/backgrounds/${w.id}.webp"
        else -> null
    }
    val image = rememberWebImage(env.api, path)
    Box(modifier.background(wallpaperBrush(w, light))) {
        if (image != null) Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        content()
    }
}

/** Hintergrund des Arbeitsplatzes (Reiter „Hintergrund“): Vorschau und Auswahl. */
@Composable
internal fun WallpaperGroup(s: LookState) {
    val ov = LocalOverlays.current
    val env = LocalEnv.current
    val light = !Titan.colors.dark
    val w = wallpaperOf(s.desktop)
    SettingGroup(t("desktop.wallpaper.title")) {
        SettingRow(t("look.desktop"), desc = t("look.wallpaperHint"), control = {
            WallpaperFill(w, light, Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Titan.colors.border, RoundedCornerShape(8.dp)))
            SmallButton(t("look.wallpaper"), { ov.show { close -> WallpaperDialog(env, s, close) } })
        })
    }
}

@Composable
private fun BoxScope.WallpaperDialog(env: SettingsEnv, s: LookState, close: () -> Unit) {
    val tr = tr()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val light = !Titan.colors.dark
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val w = wallpaperOf(s.desktop)
    fun setWall(next: JsonObject) {
        val d = s.desktop ?: JsonObject(emptyMap())
        s.changeDesktop(d.with("wallpaper" to next))
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = ""
        scope.launch {
            try {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("no data")
                Raw.send(env.api, "background", bytes, mime)
                setWall(jobj("source" to "custom", "version" to System.currentTimeMillis(), "live" to w.live))
            } catch (e: Exception) {
                error = tr.t("desktop.wallpaper.failed") + " " + tr.errorOf(e)
            }
            busy = false
        }
    }
    ConfirmDialog(
        title = t("desktop.wallpaper.title"),
        text = null,
        actions = listOf(DialogAction(t("desktop.widgets.done")) { close() }),
        onDismiss = close,
        body = {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in WALLPAPERS) {
                        val on = !w.custom && w.id == id
                        val shape = RoundedCornerShape(10.dp)
                        WallpaperFill(
                            Wall(false, id, 0, w.live), light,
                            Modifier.size(104.dp, 64.dp).clip(shape).border(if (on) 3.dp else 1.dp, if (on) Titan.colors.accent else Titan.colors.border, shape)
                                .clickable(role = Role.RadioButton) { setWall(jobj("source" to "builtin", "id" to id, "live" to w.live)) }
                                .semantics(mergeDescendants = true) { stateDescription = if (on) "✓" else "" },
                        ) {
                            Text(
                                t("desktop.wallpaper.$id"),
                                style = TitanType.small.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .45f)).padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Buttons {
                    SmallButton(t("desktop.wallpaper.upload"), { upload.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, enabled = !busy)
                    Muted(t("desktop.wallpaper.uploadHint"))
                }
                if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
                CheckRow(w.live, { live ->
                    val d = s.desktop ?: JsonObject(emptyMap())
                    setWall(d.o("wallpaper").with("live" to live))
                }, t("desktop.wallpaper.live"), desc = t("desktop.wallpaper.liveHint"))
            }
        },
    )
}

// ---- Programme (web/src/lib/desktop/programs.svelte.ts, apps.ts) ----

internal data class Program(val key: String, val label: String, val glyph: String, val gradient: String)

/** SYSTEM_APPS im Web: Kennung, Text, Glyphe, Verlauf, nur Admins, Dienst, nur wenn eingeschaltet. */
private data class SystemApp(val id: String, val glyph: String, val gradient: String, val admin: Boolean, val service: String? = null, val optIn: Boolean = false)

private val SYSTEM_APPS = listOf(
    SystemApp("files", "files", "orange", false), SystemApp("shares", "shares", "yellow", true), SystemApp("storage", "storage", "dark", true),
    SystemApp("docker", "docker", "blue", true, "docker"), SystemApp("vms", "vm", "violet", true, "vms"), SystemApp("lxc", "lxc", "teal", true, "lxc"),
    SystemApp("appcenter", "store", "pink", true), SystemApp("backup", "backup", "green", true), SystemApp("nvr", "nvr", "red", true),
    SystemApp("mail", "mail", "blue", false, optIn = true), SystemApp("calendar", "calendar", "violet", false, optIn = true),
    SystemApp("contacts", "contacts", "green", false, optIn = true), SystemApp("agent", "agent", "ember", false, optIn = true),
    SystemApp("monitor", "monitor", "dark", true), SystemApp("logs", "logs", "grey", true), SystemApp("planner", "planner", "teal", true),
    SystemApp("terminal", "terminal", "dark", true), SystemApp("users", "users", "blue", true), SystemApp("security", "shield", "green", true),
    SystemApp("account", "account", "grey", false), SystemApp("settings", "settings", "grey", false),
)

private val DOCK_DEFAULT_PINNED = listOf("app:files", "app:docker", "app:vms", "app:agent", "app:monitor", "app:settings")
private val LETTER_GRADIENTS = listOf("blue", "violet", "teal", "green", "orange", "pink", "red", "yellow")

/** Die Programme, die dieses Konto hat: Systemprogramme, Docker-Apps, Web-Apps. */
internal suspend fun loadPrograms(env: SettingsEnv, tr: io.github.ra5on.titanos.ui.i18n.Tr): List<Program> {
    val services = runCatching { env.api.obj("services/state") }.getOrDefault(JsonObject(emptyMap())).toMutableMap()
    services["mail"] = JsonPrimitive(runCatching { env.api.obj("mail/access").bool("enabled") }.getOrDefault(false))
    val out = SYSTEM_APPS.filter { app ->
        if (app.admin && !env.admin) return@filter false
        val on = (services[app.id] as? JsonPrimitive)?.content
        if (app.optIn) on == "true" else app.service == null || (services[app.service] as? JsonPrimitive)?.content != "false"
    }.map { Program("app:" + it.id, tr.t("desktop.app.${it.id}"), it.glyph, it.gradient) }.toMutableList()
    runCatching { env.api.obj("apps/mine").a("apps").objects() }.getOrNull()?.let { mine ->
        out += mine.map { m -> Program("docker:" + m.str("app"), m.str("title").ifEmpty { m.str("app") }, "", "blue") }.sortedBy { it.label.lowercase() }
    }
    runCatching { env.api.obj("webapps").a("apps").objects() }.getOrNull()?.let { apps ->
        out += apps.filter { it.bool("installed") && it.str("state") == "ready" && it.str("url").isNotEmpty() }.map { w ->
            val id = w.str("id")
            var h = 0L
            for (ch in id) h = (h * 31 + ch.code) and 0xFFFFFFFFL
            Program("webapp:$id", w.str("name"), "", LETTER_GRADIENTS[(h % LETTER_GRADIENTS.size).toInt()])
        }
    }
    return out
}

// ---- Reiter „Dock“ (DockSettings.svelte) ----

private fun defaultDock() = jobj("show" to true, "pinned" to JsonArray(emptyList()), "customPinned" to false, "size" to 52, "opacity" to 90, "magnify" to 1, "position" to "bottom", "border" to true)

@Composable
internal fun DockTab(s: LookState) {
    val env = LocalEnv.current
    val tr = tr()
    var programs by remember { mutableStateOf<List<Program>?>(null) }
    var adding by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { programs = loadPrograms(env, tr) }
    val desktop = s.desktop ?: JsonObject(emptyMap())
    val dock = JsonObject(defaultDock() + desktop.o("dock"))
    val all = programs
    if (all == null) {
        LoadingLine(t("common.loading"))
        return
    }
    val known = all.map { it.key }.toSet()
    val byKey = all.associateBy { it.key }
    val pinned = (if (dock.bool("customPinned")) dock.strings("pinned") else DOCK_DEFAULT_PINNED).filter { it in known }
    fun change(next: JsonObject) = s.changeDesktop(desktop.with("dock" to next))
    fun setPinned(list: List<String>) = change(dock.with("pinned" to list, "customPinned" to true))
    val show = dock.boolOrNull("show") != false
    val size = dock.int("size", 52)
    val position = dock.str("position", "bottom")

    SettingGroup(t("look.dock.preview")) {
        val light = !Titan.colors.dark
        WallpaperFill(
            wallpaperOf(desktop), light,
            Modifier.fillMaxWidth().height(if (position == "bottom") 170.dp else (pinned.size * (size + 8) + 48).coerceIn(180, 560).dp)
                .clip(RoundedCornerShape(12.dp)).semantics { contentDescription = tr.t("look.dock.preview") },
        ) {
            if (show) {
                val plate = Titan.colors.surface.copy(alpha = dock.int("opacity", 90) / 100f)
                val shape = RoundedCornerShape(18.dp)
                val plateMod = Modifier.clip(shape).background(plate).then(if (dock.boolOrNull("border") != false) Modifier.border(1.dp, Titan.colors.border, shape) else Modifier).padding(8.dp)
                val iconSize = (size * 0.75f).dp
                when (position) {
                    "left", "right" -> Column(
                        Modifier.align(if (position == "left") Alignment.CenterStart else Alignment.CenterEnd).padding(12.dp).then(plateMod),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { for (k in pinned) byKey[k]?.let { AppIcon(it.glyph, it.gradient, iconSize) } }
                    else -> Row(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp).horizontalScroll(rememberScrollState()).then(plateMod),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) { for (k in pinned) byKey[k]?.let { AppIcon(it.glyph, it.gradient, iconSize) } }
                }
            } else {
                Text(t("look.dock.hiddenNote"), style = TitanType.body, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(10.dp))
            }
        }
    }

    SettingGroup(t("look.dock.look"), desc = t("look.dock.intro"), tools = { SmallButton(t("desktop.dock.reset"), { change(JsonObject(dock + defaultDock())) }) }) {
        SettingRow(t("look.dock.show"), summary = if (show) t("look.on") else t("look.off"), control = {
            Toggle(show, { change(dock.with("show" to it)) }, t("look.on"), t("look.off"), label = t("look.dock.show"))
        })
        SettingRow(t("desktop.dock.position"), control = {
            Segments(position, listOf("bottom" to t("desktop.dock.bottom"), "left" to t("desktop.dock.left"), "right" to t("desktop.dock.right")), { change(dock.with("position" to it)) })
        })
        LookSlider(t("desktop.dock.size"), null, size, 36..80, 2, "px") { change(dock.with("size" to it)) }
        LookSlider(t("desktop.dock.opacity"), null, dock.int("opacity", 90), 30..100, 5, "%") { change(dock.with("opacity" to it)) }
        SettingRow(t("desktop.dock.magnify"), control = {
            Segments(dock.int("magnify", 1), listOf(0 to t("desktop.dock.magnifyOff"), 1 to t("desktop.dock.magnifyLight"), 2 to t("desktop.dock.magnifyStrong")), { change(dock.with("magnify" to it)) })
        })
        SettingRow(t("desktop.dock.border"), control = {
            Segments(dock.boolOrNull("border") != false, listOf(true to t("desktop.dock.borderOn"), false to t("desktop.dock.borderOff")), { change(dock.with("border" to it)) })
        })
    }

    SettingGroup(t("look.dock.apps"), desc = t("look.dock.appsHint")) {
        if (pinned.isEmpty()) Muted(t("desktop.dock.empty"))
        pinned.forEachIndexed { i, key ->
            val p = byKey[key]
            val name = p?.label ?: key
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Titan.colors.surface).border(1.dp, Titan.colors.border, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (p != null) AppIcon(p.glyph, p.gradient, 30.dp)
                Text(name, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconKey(WebIcons["arrowUp"], t("look.dock.up", "name" to name), { setPinned(pinned.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0)
                    IconKey(WebIcons["arrowDown"], t("look.dock.down", "name" to name), { setPinned(pinned.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < pinned.size - 1)
                    IconKey(WebIcons["close"], t("desktop.dock.unpinNamed", "name" to name), { setPinned(pinned - key) })
                }
            }
        }
        val addable = all.filter { it.key !in pinned }.sortedBy { it.label.lowercase() }
        if (addable.isNotEmpty()) {
            Buttons(Modifier.padding(top = 6.dp)) {
                Muted(t("look.dock.add"))
                Select(adding, listOf("" to t("look.dock.choose")) + addable.map { it.key to it.label }, { adding = it }, label = t("look.dock.add"))
                SmallButton(t("look.dock.addButton"), { if (adding.isNotEmpty() && adding !in pinned) setPinned(pinned + adding); adding = "" }, enabled = adding.isNotEmpty())
            }
        }
        Muted(t("look.dock.rightClickHint"))
    }

    SettingGroup(t("desktop.bar.title")) {
        val load = desktop.boolOrNull("barLoad") != false
        SettingRow(t("desktop.bar.load"), desc = t("desktop.bar.loadHint"), summary = if (load) t("look.on") else t("look.off"), control = {
            Toggle(load, { s.changeDesktop(desktop.with("barLoad" to it)) }, t("look.on"), t("look.off"), label = t("desktop.bar.load"))
        })
    }
    SettingGroup(t("phone.settings.title")) {
        val phone = desktop.o("phone")
        val on = phone.o("dock").boolOrNull("show") != false
        SettingRow(t("phone.settings.dock"), desc = t("phone.settings.dockHint"), summary = if (on) t("look.on") else t("look.off"), control = {
            Toggle(on, { s.changeDesktop(desktop.with("phone" to phone.with("dock" to phone.o("dock").with("show" to it)))) }, t("look.on"), t("look.off"), label = t("phone.settings.dock"))
        })
    }
}
