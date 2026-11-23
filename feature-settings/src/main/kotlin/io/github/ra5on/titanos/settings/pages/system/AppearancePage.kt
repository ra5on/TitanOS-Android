package io.github.ra5on.titanos.settings.pages.system

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.boolOrNull
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tabs
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanAppearance
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor

// ---- web/src/lib/theme/themes.ts (was die Seite braucht) ----

internal val DARK_THEMES = listOf("graphit", "schwarz", "mitternacht", "eigen")
internal val ACCENT_IDS = listOf("blau", "gruen", "tuerkis", "violett", "kupfer", "rose")
private val CUSTOM_FIELDS = listOf("bg", "surface", "header", "text", "border")
private val EFFECT_IDS = listOf("transitions", "skeleton", "micro", "pulse", "gradient", "illustrations", "shortcuts")

/** defaultAppearance() im Web. */
internal val DEFAULT_LOOK: JsonObject = SettingsApi.JSON.parseToJsonElement(
    """{"theme":"graphit","accent":"blau","customAccent":"#E5B92E","style":"glass","glass":45,"blur":18,"shine":20,"rTile":14,"rCtl":10,"font":100,
    "density":"normal","bg":"shapes","dim":30,"bgVersion":0,"custom":{"bg":"#101418","surface":"#1A2027","header":"#13181D","text":"#ECEFF3","border":"#2A333D"},
    "glow":0,"glowColor":"","motion":true,"duration":450,
    "fx":{"enabled":true,"transitions":true,"skeleton":true,"micro":true,"pulse":false,"gradient":false,"illustrations":true,"shortcuts":true},
    "menus":{"own":false,"glass":30,"blur":24,"shine":20},"dialogs":{"own":false,"glass":30,"blur":24,"shine":20,"dim":70},
    "bars":{"own":false,"glass":30,"blur":24,"shine":20,"mode":"opaque"},"sidebar":{"own":false,"glass":30,"blur":24,"shine":20,"mode":"tiles"},
    "nav":{"order":[],"hidden":[],"layout":"top","magnify":true},"saturation":100}""",
) as JsonObject

private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

/** globalLook im Web: die Regler gelten überall; Menüs und Dialoge folgen den Kacheln. */
internal fun globalLook(a: JsonObject): JsonObject {
    val glass = a.int("glass", 45)
    val blur = a.int("blur", 18)
    return a.with(
        "style" to if (glass == 0 && blur == 0) "flat" else "glass",
        "menus" to a.o("menus").with("own" to false),
        "dialogs" to a.o("dialogs").with("own" to false),
    )
}

/** withCorners im Web: Ecken der Kacheln, Knöpfe und Felder folgen. */
internal fun withCorners(a: JsonObject, radius: Int): JsonObject {
    val r = radius.coerceIn(0, 32)
    return a.with("rTile" to r, "rCtl" to minOf(22, jsRound(r * 0.7)))
}

/** Wo „Deckkraft“ auf dem Regler von durchsichtig (0) bis deckend (100) liegt. */
internal fun liquidOf(glass: Int): Int = (jsRound((80 - glass) / 0.75 / 5) * 5).coerceIn(0, 100)

/** Die Palette eines Themas wie resolvePalette (auto: nach dem Handy). */
internal fun paletteOf(look: JsonObject, theme: String, systemDark: Boolean): TitanAppearance.Palette {
    if (theme == "eigen") {
        val c = look.o("custom")
        fun hex(k: String, d: String) = TitanAppearance.parseHex(c.str(k).takeIf { HEX.matches(it) } ?: d)
        val bg = hex("bg", "#101418")
        val text = hex("text", "#ECEFF3")
        val dark = TitanAppearance.luminance(bg) < 0.4
        val surface = hex("surface", "#1A2027")
        return TitanAppearance.Palette(
            bg, surface, hex("header", "#13181D"), hex("border", "#2A333D"), text,
            if (dark) TitanAppearance.shade(text, -0.32) else TitanAppearance.shade(text, 0.38), hex("border", "#2A333D"),
            if (dark) TitanAppearance.shade(surface, 0.06) else TitanAppearance.shade(surface, -0.06), dark,
        )
    }
    val id = if (theme == "auto") (if (systemDark) "graphit" else "hell") else theme
    return TitanAppearance.THEMES[id] ?: TitanAppearance.THEMES.getValue("graphit")
}

internal fun Long.color() = Color(this)

/**
 * Der Zustand der Darstellung je Konto: `GET /preferences` › appearance, jede
 * Änderung sofort sichtbar und 0,7 s nach der letzten gespeichert
 * (`PUT /preferences/appearance`, ganzes Objekt). Thema und Akzent wirken
 * gleich in der ganzen App ([TitanAppearance.takeAccount]).
 */
@Stable
internal class LookState {
    var look by mutableStateOf<JsonObject?>(null)
    var pending by mutableStateOf<JsonObject?>(null)
    var state by mutableStateOf("idle")
    var error by mutableStateOf("")
    var desktop by mutableStateOf<JsonObject?>(null)
    var desktopPending by mutableStateOf<JsonObject?>(null)
    /** Der Stand beim Laden: geschrieben werden nur die Felder, die hier geändert wurden (der Arbeitsplatz schreibt mit). */
    var desktopBase by mutableStateOf<JsonObject?>(null)
    var loadError by mutableStateOf<Throwable?>(null)
    var round by mutableIntStateOf(0)

    fun change(next: JsonObject) {
        look = next
        pending = globalLook(next)
        state = "saving"
        round++
    }

    fun changeDesktop(next: JsonObject) {
        desktop = next
        desktopPending = next
    }
}

@Composable
fun AppearancePage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = tr()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { LookState() }
    val icon = remember { AppIconState() }
    val login = remember { LoginLookState() }

    suspend fun load() {
        try {
            val prefs = env.api.obj("preferences")
            val look = prefs["appearance"] as? JsonObject ?: DEFAULT_LOOK
            s.look = look
            // Mit „eigene Einrichtung für Android“ (Arbeitsplatz) die Einrichtung, die der Arbeitsplatz zeigt.
            val kind = desktopKind(context)
            // Steht in `androiddesktop` nur der Startbildschirm der App (`phone`), gilt noch die Einrichtung des Webs.
            s.desktop = ((prefs[kind] as? JsonObject)?.takeIf { o -> o.keys.any { it != "phone" && it != "version" } } ?: prefs["desktop"] as? JsonObject) ?: JsonObject(emptyMap())
            s.desktopBase = s.desktop
            s.loadError = null
            runCatching { TitanAppearance.takeAccount(context, org.json.JSONObject(look.toString())) }
        } catch (e: Exception) {
            s.loadError = e
        }
    }
    LaunchedEffect(Unit) { load() }

    // Speichern 0,7 s nach der letzten Änderung (wie im Web).
    LaunchedEffect(s.round) {
        val value = s.pending ?: return@LaunchedEffect
        delay(700)
        Guard.require(context, GuardAction.Settings, onDenied = { s.state = "idle" }) {
            scope.launch {
                try {
                    env.api.put("preferences/appearance", value)
                    if (s.pending == value) s.pending = null
                    s.state = "saved"
                    s.error = ""
                    runCatching { TitanAppearance.takeAccount(context, org.json.JSONObject(value.toString())) }
                } catch (e: Exception) {
                    s.state = "error"
                    s.error = tr.errorOf(e)
                }
            }
        }
    }
    // Einstellungen des Arbeitsplatzes (Dock, Leiste, Hintergrund): 1,5 s nach der letzten Änderung.
    val desktopPending = s.desktopPending
    LaunchedEffect(desktopPending) {
        val value = desktopPending ?: return@LaunchedEffect
        delay(1500)
        Guard.require(context, GuardAction.Settings) {
            scope.launch {
                try {
                    putDesktop(env.api, desktopKind(context), s.desktopBase, value)
                    s.desktopBase = value
                    if (s.desktopPending == value) s.desktopPending = null
                } catch (e: Exception) {
                    s.error = tr.t("desktop.toast.prefs", "reason" to tr.errorOf(e))
                    s.state = "error"
                }
            }
        }
    }
    // Eine Änderung kurz vor dem Verlassen geht trotzdem noch hinaus (wie keepalive im Web).
    val api by rememberUpdatedState(env.api)
    DisposableEffect(Unit) {
        onDispose {
            if (!Guard.enabled(context, GuardAction.Settings)) {
                s.pending?.let { v -> Background.launch { runCatching { api.put("preferences/appearance", v) }.onSuccess { runCatching { TitanAppearance.takeAccount(context.applicationContext, org.json.JSONObject(v.toString())) } } } }
                s.desktopPending?.let { v -> val base = s.desktopBase; val kind = desktopKind(context); Background.launch { runCatching { putDesktop(api, kind, base, v) } } }
            }
        }
    }

    SettingGroup {
        SettingRow(
            stringResource(R.string.set_app_look),
            desc = stringResource(R.string.set_app_look_desc),
            icon = WebIcons["device"],
            onClick = { a.openPage("app/look") },
        )
    }
    PageIntro(t("look.intro"))
    val tabs = listOf("colors", "glass", "background", "dock", "login").filter { it != "login" || env.admin }
    val tab = a.tab?.takeIf { it in tabs } ?: if (a.nav.page == "appearance/dock") "dock" else "colors"
    Tabs(tab, tabs.map { it to t("look.tab.$it") }, { a.tab = if (it == "colors") null else it })
    when (s.state) {
        "saving" -> Muted(t("ui.saving"))
        "saved" -> Muted(t("appearance.saved"))
        "error" -> Banner(s.error, tone = Tone.Warn)
    }
    s.loadError?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { scope.launch { load() } }) }
    val look = s.look
    if (look == null) {
        if (s.loadError == null) LoadingLine(t("common.loading"))
        return
    }
    when (tab) {
        "colors" -> ColorsTab(s, look)
        "glass" -> DetailsTab(s, look)
        "background" -> {
            WallpaperGroup(s)
            if (env.admin) AppIconEditor(icon)
        }
        "dock" -> DockTab(s)
        "login" -> LoginLookEditor(login, look)
    }
    if (env.admin) ExtrasBar(icon, login)
}

/** Die Leiste „Übernehmen / Verwerfen“ für App-Symbol und Anmeldeseite (für das ganze NAS). */
@Composable
private fun ExtrasBar(icon: AppIconState, login: LoginLookState) {
    val env = LocalEnv.current
    val tr = tr()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val dirty = icon.dirty || login.dirty
    if (!dirty && message == null) return
    SettingGroup {
        Text(
            when {
                busy -> t("ui.saving")
                dirty -> t("appearance.unsaved")
                else -> message?.first.orEmpty()
            },
            style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold),
            color = if (message?.second == true && !dirty) Titan.colors.warnText else Titan.colors.text,
        )
        if (message?.second == true && dirty) Banner(message!!.first, tone = Tone.Error)
        if (dirty) {
            Buttons {
                SmallButton(t("appearance.discard"), { icon.discard(); login.discard(); message = null }, enabled = !busy)
                SmallButton(t("appearance.apply"), {
                    Guard.require(context, GuardAction.Settings) {
                        busy = true
                        message = null
                        scope.launch {
                            val failures = mutableListOf<String>()
                            for ((area, changed, save) in listOf(
                                Triple(tr.t("appIcon.title"), icon.dirty, suspend { icon.save(env, tr) }),
                                Triple(tr.t("loginLook.title"), login.dirty, suspend { login.save(env, tr) }),
                            )) {
                                if (!changed) continue
                                try {
                                    save()
                                } catch (e: Exception) {
                                    failures += tr.t("appearance.saveFailed", "area" to area, "error" to tr.errorOf(e))
                                }
                            }
                            message = if (failures.isNotEmpty()) failures.joinToString(" · ") to true else tr.t("appearance.saved") to false
                            busy = false
                        }
                    }
                }, kind = io.github.ra5on.titanos.ui.components.ButtonKind.Primary, enabled = !busy)
            }
        }
    }
}

// ---- Reiter „Farben und Schrift“ ----

@Composable
private fun ColorsTab(s: LookState, look: JsonObject) {
    val tr = tr()
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val theme = look.str("theme", "graphit")
    var lastDark by remember { mutableStateOf(if (theme in DARK_THEMES) theme else "graphit") }
    val mode = when (theme) { "auto" -> "auto"; "hell" -> "light"; else -> "dark" }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val theme1 = io.github.ra5on.titanos.settings.common.jobj("titanosTheme" to 1).let { head -> JsonObject(head + look.without("bgVersion")) }
            context.contentResolver.openOutputStream(uri)?.use { it.write((prettyJson(theme1) + "\n").toByteArray()) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val data = runCatching { context.contentResolver.openInputStream(uri)?.use { SettingsApi.JSON.parseToJsonElement(it.readBytes().decodeToString()) } }.getOrNull() as? JsonObject
            if (data == null || !data.containsKey("titanosTheme")) {
                s.state = "error"; s.error = tr.t("appearance.importFailed")
            } else {
                s.change(JsonObject(look + data.without("titanosTheme", "bgVersion", "nav")))
            }
        }
    }
    SettingGroup(t("look.colors")) {
        SettingRow(t("look.mode"), control = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((m, label) in listOf("light" to t("look.mode.light"), "dark" to t("look.mode.dark"), "auto" to t("look.mode.auto"))) {
                    ModeCard(label, mode == m, paletteOf(look, "hell", systemDark), paletteOf(look, lastDark, systemDark), m) {
                        s.change(look.with("theme" to when (m) { "light" -> "hell"; "auto" -> "auto"; else -> lastDark }))
                    }
                }
            }
        })
        if (mode == "dark") {
            SettingRow(t("look.variant"), control = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in DARK_THEMES) {
                        val p = paletteOf(look, id, systemDark)
                        Choice(t("theme.$id") + if (id == "eigen") " …" else "", theme == id, swatch = p.bg.color()) {
                            lastDark = id
                            s.change(look.with("theme" to id))
                        }
                    }
                }
            })
            if (theme == "eigen") {
                SettingRow(t("look.customColors"), control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (field in CUSTOM_FIELDS) {
                            val value = look.o("custom").str(field).takeIf { HEX.matches(it) } ?: DEFAULT_LOOK.o("custom").str(field)
                            Choice(t("custom.$field"), false, swatch = hexColor(value)) {
                                ov.pickColor(tr.t("custom.$field"), value, tr.t("desktop.widgets.done"), tr.t("common.cancel")) { s.change(look.withPath("custom", field, value = it)) }
                            }
                        }
                    }
                })
                val p = paletteOf(look, "eigen", systemDark)
                val ratio = TitanAppearance.contrast(p.text, p.surface)
                if (ratio < 4.5) Banner(t("appearance.contrastWarning", "ratio" to io.github.ra5on.titanos.settings.common.Fmt.number(tr, ratio, 1) + ":1"), tone = Tone.Warn)
            }
        }
        SettingRow(t("appearance.accent"), control = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val accent = look.str("accent", "blau")
                for (id in ACCENT_IDS) ColorDot(TitanAppearance.ACCENTS.getValue(id).first.color(), t("accent.$id"), accent == id) { s.change(look.with("accent" to id)) }
                val own = look.str("customAccent").takeIf { HEX.matches(it) } ?: "#E5B92E"
                Choice(t("look.accentCustom"), accent == "custom", swatch = hexColor(own)) {
                    ov.pickColor(tr.t("appearance.customAccent"), own, tr.t("desktop.widgets.done"), tr.t("common.cancel")) { s.change(look.with("customAccent" to it, "accent" to "custom")) }
                }
            }
        })
    }

    SettingGroup(t("look.langText")) {
        SettingRow(t("lang.label"), desc = t("look.languageHint"), control = {
            Segments(tr.lang, listOf("de" to t("lang.de"), "en" to t("lang.en")), { lang ->
                runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(lang) }
            })
        })
        LookSlider(t("appearance.fontSize"), t("look.fontHint"), look.int("font", 100), 90..120, 5, "%") { s.change(look.with("font" to it)) }
    }

    SettingGroup(t("look.sliders")) {
        LiquidPreview(look, s.desktop, systemDark)
        val glass = look.int("glass", 45)
        val liquid = liquidOf(glass)
        SettingRow(t("look.liquid"), desc = t("look.liquidHint"), control = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(t("look.liquidClear"))
                val valueText = t("look.liquidValue", "value" to liquid)
                val label = t("look.liquid")
                val c = Titan.colors
                androidx.compose.material3.Slider(
                    value = liquid.toFloat(),
                    onValueChange = { f ->
                        val v = jsRound(f / 5.0) * 5
                        if (v != liquid) s.change(look.with("glass" to jsRound((80 - 0.75 * v) / 5) * 5, "blur" to jsRound((10 + 0.2 * v) / 2) * 2))
                    },
                    valueRange = 0f..100f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.track),
                    modifier = Modifier.weight(1f).widthIn(min = 100.dp).semantics { contentDescription = label; stateDescription = valueText },
                )
                Muted(t("look.liquidTinted"))
            }
        })
        LookSlider(t("look.corners"), t("look.cornersHint"), look.int("rTile", 14), 0..32, 2, "px") { s.change(withCorners(look, it)) }
        LookSlider(t("look.saturation"), t("look.saturationHint"), look.int("saturation", 100), 50..150, 5, "%") { s.change(look.with("saturation" to it)) }
    }

    SettingGroup(t("look.file")) {
        SettingRow(t("look.fileRow"), desc = t("look.fileHint"), control = {
            SmallButton(t("appearance.export"), { exportLauncher.launch("titanos-theme.json") })
            SmallButton(t("appearance.import"), { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) })
        })
        SettingRow(t("appearance.reset"), control = {
            // Das hochgeladene Bild bleibt auf dem NAS; nur die Einstellungen gehen zurück.
            SmallButton(t("desktop.dock.reset"), { s.change(JsonObject(look + DEFAULT_LOOK.without("bgVersion", "nav"))) })
        })
    }
}

private fun prettyJson(o: JsonElement): String = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "\t" }.encodeToString(JsonElement.serializer(), o)

// ---- Reiter „Feinheiten und Effekte“ ----

@Composable
private fun DetailsTab(s: LookState, look: JsonObject) {
    SettingGroup(t("look.details"), desc = t("look.detailsHint")) {
        SettingRow(t("appearance.density"), desc = t("look.densityHint"), control = {
            Segments(look.str("density", "normal").takeIf { it == "kompakt" } ?: "normal", listOf("normal" to t("density.normal"), "kompakt" to t("density.kompakt")), { s.change(look.with("density" to it)) })
        })
        SettingRow(t("look.motion"), desc = t("look.motionHint"), control = {
            Toggle(look.boolOrNull("motion") != false, { s.change(look.with("motion" to it)) }, t("look.on"), t("look.off"), label = t("look.motion"))
        })
        LookSlider(t("appearance.shine"), t("look.shineHint"), look.int("shine", 20), 0..60, 5, "%") { s.change(look.with("shine" to it)) }
        LookSlider(t("dashboard.visual.glow"), t("look.glowHint"), look.int("glow", 0), 0..100, 5, "%") { s.change(look.with("glow" to it)) }
        LookSlider(t("appearance.floating.dim"), t("look.dimHint"), look.o("dialogs").int("dim", 70), 0..90, 5, "%") { s.change(look.withPath("dialogs", "dim", value = it)) }
    }
    SettingGroup(t("look.effects")) {
        val fx = look.o("fx")
        val enabled = fx.boolOrNull("enabled") != false
        SettingRow(t("appearance.fx.enabled"), desc = t("look.fxHint"), control = {
            Toggle(enabled, { s.change(look.withPath("fx", "enabled", value = it)) }, t("look.on"), t("look.off"), label = t("appearance.fx.enabled"))
        })
        if (enabled) {
            for (id in EFFECT_IDS) {
                val on = fx.boolOrNull(id) ?: DEFAULT_LOOK.o("fx").bool(id)
                SettingRow(t("appearance.fx.$id"), desc = t("appearance.fx.${id}Desc"), control = {
                    Toggle(on, { s.change(look.withPath("fx", id, value = it)) }, t("look.on"), t("look.off"), label = t("appearance.fx.$id"))
                })
            }
        }
        Muted(t("appearance.fx.hint"), Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
    }
}

// ---- Bausteine ----

/** Ein Regler als Zeile mit Wert und Einheit (snippet slider im Web). */
@Composable
internal fun LookSlider(title: String, hint: String?, value: Int, range: IntRange, step: Int, unit: String, onChange: (Int) -> Unit) {
    SettingRow(title, desc = hint, control = {
        SliderField(
            value.toFloat(),
            { f -> val v = (range.first + jsRound((f - range.first) / step.toDouble()) * step).coerceIn(range.first, range.last); if (v != value) onChange(v) },
            range.first.toFloat()..range.last.toFloat(),
            title,
            "$value $unit",
        )
    })
}

/** Eine Wahl als Knopf mit Farbfeld (Varianten, eigene Farben). */
@Composable
internal fun Choice(text: String, selected: Boolean, swatch: Color? = null, onClick: () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(if (selected) c.accent.copy(alpha = .16f) else c.chip)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = if (selected) "✓" else "" }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (swatch != null) Swatch(swatch, 18.dp)
        Text(text, style = TitanType.rowSub, color = c.text)
    }
}

/** Hell, Dunkel oder Automatisch als kleines Bild mit Namen. */
@Composable
private fun ModeCard(label: String, selected: Boolean, light: TitanAppearance.Palette, dark: TitanAppearance.Palette, mode: String, onClick: () -> Unit) {
    val c = Titan.colors
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = if (selected) "✓" else "" }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = RoundedCornerShape(10.dp)
        Row(Modifier.size(88.dp, 52.dp).clip(shape).border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, shape)) {
            val halves = when (mode) { "auto" -> listOf(light, dark); "light" -> listOf(light); else -> listOf(dark) }
            for (p in halves) {
                Box(Modifier.weight(1f).fillMaxHeight().background(p.bg.color()).padding(6.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(22.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.surface.color()))
                        Box(Modifier.width(14.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(18.dp, 22.dp).clip(RoundedCornerShape(4.dp)).background(p.surface.color()).border(1.dp, p.border.color(), RoundedCornerShape(4.dp)))
                }
            }
        }
        Text(label, style = TitanType.rowSub.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = c.text)
    }
}

/** Vorschau der Deckkraft: eine Fläche über einem Hintergrundbild. */
@Composable
private fun LiquidPreview(look: JsonObject, desktop: JsonObject?, systemDark: Boolean) {
    val p = paletteOf(look, look.str("theme", "graphit"), systemDark)
    val glass = look.int("glass", 45)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(12.dp))
            .background(wallpaperBrush(wallpaperOf(desktop), !p.dark)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(12.dp).widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(p.surface.color().copy(alpha = (100 - glass) / 100f))
                .border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("15:11", style = TitanType.title, color = p.text.color())
            Text(t("look.liquidSample"), style = TitanType.small, color = p.text.color(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * Welche Einstellung der Arbeitsplatz zeigt: `desktop` wie im Web, mit
 * „eigene Einrichtung für Android“ `androiddesktop` (Schalter des
 * Arbeitsplatzes, feature-workspace WorkspaceSettings, Datei „titan-workspace“).
 */
internal fun desktopKind(context: android.content.Context): String =
    if (context.getSharedPreferences("titan-workspace", android.content.Context.MODE_PRIVATE).getBoolean("own", false)) "androiddesktop" else "desktop"

/**
 * Schreibt nur die Felder, die sich gegenüber [base] geändert haben, in den
 * Stand am NAS (neu gelesen): Änderungen des Arbeitsplatzes oder des Webs
 * seit dem Öffnen der Seite bleiben.
 */
internal suspend fun putDesktop(api: io.github.ra5on.titanos.settings.common.SettingsApi, kind: String, base: JsonObject?, value: JsonObject) {
    val changed = value.filter { (k, v) -> base?.get(k) != v }.keys + (base?.keys.orEmpty() - value.keys)
    val all = api.obj("preferences")
    val own = all[kind] as? JsonObject
    val web = all["desktop"] as? JsonObject ?: JsonObject(emptyMap())
    // In `androiddesktop` kann bisher nur der Startbildschirm der App (`phone`, PLAN 117) stehen: dann die Einrichtung des Webs als Anfang, `phone` bleibt.
    val stored = when {
        own == null -> web
        kind == "androiddesktop" && own.keys.none { it != "phone" && it != "version" } -> JsonObject(web + own)
        else -> own
    }
    val merged = JsonObject(stored.toMutableMap().apply { for (k in changed) { val v = value[k]; if (v != null) put(k, v) else remove(k) } })
    api.put("preferences/$kind", merged)
}
