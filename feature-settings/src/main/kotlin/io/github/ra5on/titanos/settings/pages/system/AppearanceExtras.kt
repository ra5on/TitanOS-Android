package io.github.ra5on.titanos.settings.pages.system

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.boolOrNull
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanAppearance
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Eine gewählte, noch nicht gespeicherte Bilddatei. */
internal class PickedFile(val bytes: ByteArray, val name: String, val mime: String) {
    val image: ImageBitmap? by lazy { decodeImage(bytes) }
}

private fun readPicked(context: android.content.Context, uri: android.net.Uri): PickedFile? = runCatching {
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "image"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PickedFile(bytes, name, mime)
}.getOrNull()

// ---- App-Symbol (AppIconEditor.svelte, für das ganze NAS) ----

@Stable
internal class AppIconState {
    var icon by mutableStateOf<JsonObject?>(null)
    var kind by mutableStateOf("default")
    var color by mutableStateOf("#6cb6ff")
    var pending by mutableStateOf<PickedFile?>(null)
    var error by mutableStateOf("")
    var started = false

    val dirty: Boolean
        get() {
            val i = icon ?: return false
            return if (kind == "image") pending != null
            else kind != i.str("kind") || (kind == "color" && color.lowercase() != i.str("color").lowercase())
        }

    fun take(next: JsonObject) {
        icon = next
        kind = next.str("kind", "default").ifEmpty { "default" }
        if (next.str("color").isNotEmpty()) color = next.str("color")
    }

    suspend fun load(env: SettingsEnv, tr: Tr) {
        if (started) return
        started = true
        try { take(env.api.obj("app-icon")) } catch (e: Exception) { error = tr.errorOf(e) }
    }

    suspend fun save(env: SettingsEnv, tr: Tr) {
        if (!dirty) return
        error = ""
        try {
            val p = pending
            val answer = if (kind == "image" && p != null) Raw.send(env.api, "app-icon", p.bytes, p.mime.takeIf { it.startsWith("image/") } ?: "image/png")
            else env.api.put("app-icon", if (kind == "color") jobj("kind" to kind, "color" to color) else jobj("kind" to kind))
            (answer as? JsonObject)?.let(::take)
            pending = null
        } catch (e: Exception) {
            error = tr.errorOf(e)
            throw e
        }
    }

    fun discard() {
        pending = null
        error = ""
        icon?.let(::take)
    }
}

@Composable
internal fun AppIconEditor(s: AppIconState) {
    val env = LocalEnv.current
    val tr = tr()
    val ov = LocalOverlays.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { s.load(env, tr) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readPicked(context, uri)?.let { s.pending = it }
    }
    SettingGroup(t("appIcon.title"), desc = t("appIcon.intro")) {
        val icon = s.icon
        FlowRow(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Titan.colors.surface).border(1.dp, Titan.colors.border, RoundedCornerShape(12.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                val saved = rememberWebImage(env.api, "/icons/app-192.png?v=${icon.long("version")}")
                val shown = if (s.kind == "image") s.pending?.image ?: saved else saved
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(Titan.colors.chip).semantics { contentDescription = tr.t("appIcon.preview") }) {
                    if (shown != null) Image(shown, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    else if (s.kind == "color") Box(Modifier.matchParentSize().background(hexColor(s.color)))
                }
            }
            Column(Modifier.widthIn(min = 200.dp)) {
                RadioList(s.kind, listOf(Triple("default", t("appIcon.default"), null), Triple("color", t("appIcon.color"), null), Triple("image", t("appIcon.image"), null)), { s.kind = it })
                if (s.kind == "color") {
                    Choice(t("appIcon.color"), false, swatch = hexColor(s.color)) {
                        ov.pickColor(tr.t("appIcon.color"), s.color, tr.t("desktop.widgets.done"), tr.t("common.cancel")) { s.color = it.lowercase() }
                    }
                }
                if (s.kind == "image") {
                    Muted(t("appIcon.imageHint"))
                    Buttons {
                        SmallButton(t("appIcon.pick"), { pick.launch(arrayOf("image/png", "image/jpeg", "image/webp")) })
                        s.pending?.let { Muted(it.name) }
                    }
                }
            }
        }
        Muted(t("appIcon.ios"), Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
        if (s.error.isNotEmpty()) Banner(s.error, tone = Tone.Error)
    }
}

// ---- Anmeldeseite (LoginAppearanceEditor.svelte, LoginAccountsSettings.svelte, lib/login/look.ts) ----

private val BACKGROUND_IDS = listOf("nordlicht", "nebelwald", "bergsee", "kueste", "wueste", "weltraum", "graphit", "mitternacht", "wellen", "geometrie")
private val LOGIN_THEMES = listOf("inherit", "graphit", "schwarz", "mitternacht", "hell", "auto")
private val LOGIN_SOURCES = listOf("theme", "builtin", "custom")
private val LOGIN_POSITIONS = listOf("center", "left", "right")
private val LOGIN_ENTERS = listOf("zoom", "curtain", "focus", "split", "none")
private val LOGIN_WIDTHS = listOf("normal", "wide")
private val LOGIN_LOCALES = listOf("de", "en")
private const val LOGIN_TITLE_MAX = 60
private const val LOGIN_NOTICE_MAX = 300
private const val LOGIN_IMAGE_MAX = 5 * 1024 * 1024
private val LOGIN_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/webp")

private val DEFAULT_LOGIN: JsonObject = SettingsApi.JSON.parseToJsonElement(
    """{"theme":"inherit","accent":"blau","customAccent":"#E5B92E","style":"glass","glass":45,"blur":18,
    "background":{"source":"theme","id":"","fit":"cover","x":50,"y":50,"zoom":100,"dim":30},"image":null,"position":"center","width":"normal",
    "texts":{},"hideHost":false,"hideLogo":false,"still":false,"enter":"zoom","showStatus":false,"clock":false,"accountsStyle":"circles",
    "showAccounts":true,"showAccountsExternal":false,"hiddenAccounts":[]}""",
) as JsonObject

private fun JsonObject.oneOf(key: String, allowed: List<String>, def: String) = str(key).takeIf { it in allowed } ?: def

/** Die bekannten Felder einer Anmeldeseite in Normalform (sanitizeLoginLook ohne Bild). */
private fun loginKnown(v: JsonObject): JsonObject {
    val b = v.o("background")
    val source = b.oneOf("source", LOGIN_SOURCES, "theme")
    val texts = LOGIN_LOCALES.mapNotNull { l ->
        val e = v.o("texts").o(l)
        val title = e.str("title").trim()
        val notice = e.str("notice").trim()
        if (title.isEmpty() && notice.isEmpty()) null else l to jobj("title" to title, "notice" to notice)
    }.toMap()
    return jobj(
        "theme" to v.oneOf("theme", LOGIN_THEMES, "inherit"),
        "accent" to v.oneOf("accent", ACCENT_IDS + "custom", "blau"),
        "customAccent" to (v.str("customAccent").takeIf { HEX.matches(it) }?.uppercase() ?: "#E5B92E"),
        "style" to v.oneOf("style", listOf("flat", "glass", "outline"), "glass"),
        "glass" to v.int("glass", 45), "blur" to v.int("blur", 18),
        "background" to jobj(
            "source" to source, "id" to if (source == "builtin") b.str("id") else "",
            "fit" to if (b.str("fit") == "contain") "contain" else "cover",
            "x" to b.int("x", 50), "y" to b.int("y", 50), "zoom" to b.int("zoom", 100), "dim" to b.int("dim", 30),
        ),
        "position" to v.oneOf("position", LOGIN_POSITIONS, "center"), "width" to v.oneOf("width", LOGIN_WIDTHS, "normal"),
        "texts" to JsonObject(texts), "hideHost" to v.bool("hideHost"), "hideLogo" to v.bool("hideLogo"), "still" to v.bool("still"),
        "enter" to v.oneOf("enter", LOGIN_ENTERS, "zoom"), "showStatus" to v.bool("showStatus"), "clock" to v.bool("clock"),
        "accountsStyle" to if (v.str("accountsStyle") == "list") "list" else "circles",
        "showAccounts" to (v.boolOrNull("showAccounts") != false), "showAccountsExternal" to v.bool("showAccountsExternal"),
        "hiddenAccounts" to v.strings("hiddenAccounts").sorted(),
    )
}

private fun JsonObject.image(): JsonObject? = (this["image"] as? JsonObject)?.takeIf { Regex("^[a-f0-9]{64}$").matches(it.str("id")) }

/** `top` mit Deckkraft [alpha] über `bottom` (composite im Web). */
private fun composite(top: Long, alpha: Double, bottom: Long): Long {
    fun ch(c: Long, s: Int) = ((c shr s) and 255).toDouble()
    fun mix(s: Int) = Math.round(ch(top, s) * alpha + ch(bottom, s) * (1 - alpha)).coerceIn(0, 255)
    return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

@Stable
internal class LoginLookState {
    var saved by mutableStateOf<JsonObject?>(null)
    var draft by mutableStateOf<JsonObject?>(null)
    var pending by mutableStateOf<PickedFile?>(null)
    var message by mutableStateOf<Pair<String, Boolean>?>(null)
    var accounts by mutableStateOf<List<JsonObject>?>(null)
    var hostname by mutableStateOf("")
    var started = false

    val dirty: Boolean get() = pending != null || (draft != null && draft != saved)
    val removesImage: Boolean get() = saved?.image() != null && draft.o("background").str("source") != "custom"

    suspend fun load(env: SettingsEnv) {
        if (started) return
        started = true
        val look = runCatching { env.api.obj("login-appearance") }.getOrNull() ?: DEFAULT_LOGIN
        saved = look; draft = look
        accounts = runCatching { (env.api.get("users") as? JsonArray)?.objects()?.sortedBy { it.str("name").lowercase() } }.getOrNull() ?: emptyList()
        hostname = runCatching { env.api.obj("session").str("hostname") }.getOrDefault("")
    }

    fun change(next: JsonObject) { draft = next }

    /** Was an titand geht: kein Bild, die Kennung des Motivs nur für „builtin“. */
    private fun body(d: JsonObject, removeImage: Boolean): JsonObject {
        var out = d.without("image", "configured")
        val b = out.o("background")
        if (b.str("source") != "builtin") out = out.with("background" to b.without("id"))
        if (removeImage) out = out.with("removeImage" to true)
        return out
    }

    suspend fun save(env: SettingsEnv, tr: Tr) {
        val d = draft ?: return
        if (!dirty) return
        message = null
        try {
            val p = pending
            val noImage = d.with("image" to JsonNull)
            val result: kotlinx.serialization.json.JsonElement? = when {
                p == null && loginKnown(noImage) == loginKnown(DEFAULT_LOGIN) && (saved?.image() == null || removesImage) -> { env.api.delete("login-appearance"); null }
                p != null && d.o("background").str("source") == "custom" -> env.api.upload(
                    "login-appearance", "image", p.name, p.bytes, p.mime,
                    extra = mapOf("settings" to SettingsApi.JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body(d, false))),
                    method = "PUT",
                )
                else -> env.api.put("login-appearance", body(d, removesImage))
            }
            val next = result as? JsonObject ?: runCatching { env.api.obj("login-appearance") }.getOrNull() ?: DEFAULT_LOGIN
            pending = null
            saved = next
            draft = next
        } catch (e: Exception) {
            message = tr.errorOf(e) to true
            throw e
        }
    }

    fun discard() {
        pending = null
        draft = saved
        message = null
    }

    fun reset() {
        pending = null
        draft = DEFAULT_LOGIN.with("image" to (saved?.image() ?: JsonNull))
        message = null
    }
}

@Composable
internal fun LoginLookEditor(s: LoginLookState, account: JsonObject) {
    val env = LocalEnv.current
    val tr = tr()
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    LaunchedEffect(Unit) { s.load(env) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val d = s.draft ?: return@rememberLauncherForActivityResult
        s.message = null
        val f = readPicked(context, uri)
        when {
            f == null -> s.message = tr.t("dashboard.visual.imageFailed") to true
            f.mime !in LOGIN_IMAGE_TYPES -> s.message = tr.t("dashboard.visual.imageType") to true
            f.bytes.size > LOGIN_IMAGE_MAX -> s.message = tr.t("loginLook.imageTooLarge") to true
            f.image == null -> s.message = tr.t("dashboard.visual.imageFailed") to true
            else -> {
                val id = MessageDigest.getInstance("SHA-256").digest(f.bytes).joinToString("") { "%02x".format(it) }
                s.pending = f
                // Gezeigt bis zum Speichern; titand legt seine eigene Kopie an.
                s.change(d.with("image" to jobj("id" to id, "width" to f.image!!.width, "height" to f.image!!.height, "bytes" to f.bytes.size), "background" to d.o("background").with("source" to "custom", "id" to "")))
            }
        }
    }
    val d = s.draft
    if (d == null) {
        io.github.ra5on.titanos.ui.components.LoadingLine(t("common.loading"))
        return
    }
    fun set(key: String, value: Any?) = s.change(d.with(key to value))
    fun setBg(key: String, value: Any?) = s.change(d.withPath("background", key, value = value))

    // Konten auf der Anmeldeseite.
    SettingGroup(t("loginPick.title")) {
        val style = if (d.str("accountsStyle") == "list") "list" else "circles"
        val showAll = d.boolOrNull("showAccounts") != false
        SettingRow(t("loginPick.style"), desc = t("loginPick.styleHint"), control = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniPicker(circles = true, on = style == "circles")
                MiniPicker(circles = false, on = style == "list")
            }
            Segments(style, listOf("circles" to t("loginPick.circles"), "list" to t("loginPick.list")), { set("accountsStyle", it) })
        })
        SettingRow(t("loginPick.showAll"), desc = t("loginPick.showAllHint"), control = {
            Toggle(showAll, { set("showAccounts", it) }, t("look.on"), t("look.off"), label = t("loginPick.showAll"))
        })
        SettingRow(t("loginPick.external"), desc = t("loginPick.externalHint"), control = {
            Toggle(d.bool("showAccountsExternal"), { set("showAccountsExternal", it) }, t("look.on"), t("look.off"), enabled = showAll, label = t("loginPick.external"))
        })
        val accounts = s.accounts.orEmpty()
        if (accounts.isNotEmpty()) {
            SettingRow(t("loginPick.each"), desc = t("loginPick.eachHint"), id = "login-accounts-each", startOpen = true) {
                val hidden = d.strings("hiddenAccounts")
                for (acc in accounts) {
                    val name = acc.str("name")
                    val locked = acc.bool("disabled")
                    val role = when (acc.str("role")) { "owner" -> "role.owner"; "admin" -> "role.admin"; else -> "role.user" }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Titan.colors.chip), contentAlignment = Alignment.Center) {
                            Text(name.take(1).uppercase(), style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(name, style = TitanType.body, color = Titan.colors.text)
                            Muted(t(role) + if (locked) " · " + t("loginPick.locked") else "")
                        }
                        Toggle(
                            !locked && name !in hidden,
                            { shown -> val rest = hidden - name; set("hiddenAccounts", if (shown) rest else (rest + name).sorted()) },
                            t("look.on"), t("look.off"), enabled = showAll && !locked, label = t("loginPick.showNamed", "name" to name),
                        )
                    }
                }
            }
        }
        Muted(t("loginPick.note"), Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
    }

    // Wie die Seite aussieht.
    val theme = d.oneOf("theme", LOGIN_THEMES, "inherit")
    val bg = d.o("background")
    val source = bg.oneOf("source", LOGIN_SOURCES, "theme")
    val inheritAll = theme == "inherit" && source == "theme"
    val effTheme = if (theme == "inherit") account.str("theme", "graphit") else theme
    val palette = paletteOf(account, effTheme, systemDark)
    val effStyle = if (theme == "inherit") (if (account.int("glass", 45) == 0 && account.int("blur", 18) == 0) "flat" else account.str("style", "glass")) else "glass"
    val effGlass = if (theme == "inherit") account.int("glass", 45) else d.int("glass", 45)
    val effBg = if (inheritAll) account.str("bg", "shapes") else if (source != "theme") "image" else if (theme != "inherit") "shapes" else account.str("bg", "shapes")
    val picturePath = when (source) {
        "builtin" -> "/backgrounds/${bg.str("id")}.webp"
        "custom" -> d.image()?.let { "/api/v1/login-appearance/background/${it.str("id")}" }
        else -> null
    }
    val loaded = rememberWebImage(env.api, if (s.pending != null && source == "custom") null else picturePath)
    val picture = if (source == "custom") s.pending?.image ?: loaded else if (source == "builtin") loaded else null
    var average by remember(picture) { mutableStateOf<Long?>(null) }
    LaunchedEffect(picture) {
        average = picture?.let { img ->
            withContext(Dispatchers.Default) {
                runCatching {
                    val small = Bitmap.createScaledBitmap(img.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false), 16, 16, true)
                    var r = 0L; var g = 0L; var b = 0L
                    for (x in 0 until 16) for (y in 0 until 16) { val px = small.getPixel(x, y); r += (px shr 16) and 255; g += (px shr 8) and 255; b += px and 255 }
                    0xFF000000 or ((r / 256) shl 16) or ((g / 256) shl 8) or (b / 256)
                }.getOrNull()
            }
        }
    }
    val behind: Long? = if (picturePath != null || (source == "custom" && s.pending != null)) average?.let { composite(0xFF000000, bg.int("dim", 30) / 100.0, it) } else palette.bg
    val ratio = behind?.let {
        val alpha = when (effStyle) {
            "flat" -> 1.0
            "outline" -> 0.0
            else -> (1 - effGlass / 100.0).let { a -> if (effBg == "solid") a else maxOf(a, if (palette.dark) 0.35 else 0.5) }
        }
        val card = composite(palette.surface, alpha, it)
        minOf(TitanAppearance.contrast(palette.text, card), TitanAppearance.contrast(palette.muted, card))
    } ?: 21.0

    SettingGroup(t("loginLook.title"), desc = t("loginLook.intro")) {
        LoginPreview(d, palette, picture, alpha = if (effStyle == "flat") 1f else (1 - effGlass / 100f).coerceAtLeast(if (palette.dark) .35f else .5f), hostname = s.hostname, accent = accentOf(d, account, palette))
        if (ratio < 4.5) Banner(t("loginLook.contrastWarning", "ratio" to Fmt.number(tr, ratio, 1) + ":1"), tone = Tone.Warn)

        SettingRow(t("loginLook.theme"), desc = if (theme == "inherit") t("loginLook.themeInheritHint") else null, control = {
            Select(theme, LOGIN_THEMES.map { it to if (it == "inherit") t("loginLook.themeInherit") else t("theme.$it") }, { set("theme", it) }, label = t("loginLook.theme"))
        })
        if (theme != "inherit") {
            SettingRow(t("appearance.accent"), control = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val accent = d.str("accent", "blau")
                    for (id in ACCENT_IDS) ColorDot(TitanAppearance.ACCENTS.getValue(id).first.color(), t("accent.$id"), accent == id) { set("accent", id) }
                    val own = d.str("customAccent").takeIf { HEX.matches(it) } ?: "#E5B92E"
                    Choice(t("appearance.customAccent"), accent == "custom", swatch = hexColor(own)) {
                        ov.pickColor(tr.t("appearance.customAccent"), own, tr.t("desktop.widgets.done"), tr.t("common.cancel")) { s.change(d.with("customAccent" to it, "accent" to "custom")) }
                    }
                }
            })
            LookSlider(t("appearance.transparency"), null, d.int("glass", 45), 0..90, 5, "%") { set("glass", it) }
            LookSlider(t("appearance.blur"), t("loginLook.glassHint"), d.int("blur", 18), 0..40, 2, "px") { set("blur", it) }
        }

        SettingRow(t("loginLook.background"), control = {
            Select(source, LOGIN_SOURCES.map { it to t("loginLook.bg.$it") }, { src ->
                s.change(d.with("background" to bg.with("source" to src, "id" to if (src == "builtin") bg.str("id").takeIf { it in BACKGROUND_IDS } ?: BACKGROUND_IDS[0] else "")))
                if (src == "custom" && d.image() == null) pick.launch(LOGIN_IMAGE_TYPES.toTypedArray())
            }, label = t("loginLook.background"))
        }, id = "login-bg", startOpen = true) {
            if (source == "builtin") {
                FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in BACKGROUND_IDS) Motif(id, bg.str("id") == id) { setBg("id", id) }
                }
            }
            if (source == "custom") {
                Buttons {
                    val img = d.image()
                    Muted(
                        when {
                            s.pending != null -> "${s.pending!!.name} · " + t("loginLook.imagePending")
                            img != null -> "${img.int("width")} × ${img.int("height")}"
                            else -> t("loginLook.imageNone")
                        },
                    )
                    SmallButton(if (img != null) t("loginLook.replaceImage") else t("loginLook.chooseImage"), { pick.launch(LOGIN_IMAGE_TYPES.toTypedArray()) })
                }
                Muted(t("loginLook.imageHint"))
            }
            if (s.removesImage) Muted(t("loginLook.imageRemoved"))
            if (source != "theme") {
                SettingSub(t("dashboard.visual.fit"), control = {
                    Select(if (bg.str("fit") == "contain") "contain" else "cover", listOf("cover" to t("dashboard.visual.cover"), "contain" to t("dashboard.visual.contain")), { setBg("fit", it) }, label = t("dashboard.visual.fit"))
                })
                if (bg.str("fit") != "contain") {
                    SubSlider(t("dashboard.visual.zoom"), bg.int("zoom", 100), 100..200) { setBg("zoom", it) }
                    SubSlider(t("dashboard.visual.positionX"), bg.int("x", 50), 0..100) { setBg("x", it) }
                    SubSlider(t("dashboard.visual.positionY"), bg.int("y", 50), 0..100) { setBg("y", it) }
                }
                SubSlider(t("dashboard.visual.dim"), bg.int("dim", 30), 0..80) { setBg("dim", it) }
            }
        }
        if (source == "theme" && s.removesImage) Muted(t("loginLook.imageRemoved"))

        SettingRow(t("loginLook.position"), desc = t("loginLook.positionHint"), control = {
            Select(d.oneOf("position", LOGIN_POSITIONS, "center"), LOGIN_POSITIONS.map { it to t("loginLook.position.$it") }, { set("position", it) }, label = t("loginLook.position"))
        })
        SettingRow(t("loginLook.width"), control = {
            Select(d.oneOf("width", LOGIN_WIDTHS, "normal"), LOGIN_WIDTHS.map { it to t("loginLook.width.$it") }, { set("width", it) }, label = t("loginLook.width"))
        })
        SettingRow(t("loginLook.showHost"), control = {
            Column {
                CheckRow(!d.bool("hideHost"), { set("hideHost", !it) }, t("loginLook.showHost"))
                CheckRow(!d.bool("hideLogo"), { set("hideLogo", !it) }, t("loginLook.showLogo"))
                CheckRow(!d.bool("still"), { set("still", !it) }, t("loginLook.moving"), desc = t("loginLook.movingHint"))
            }
        })
        SettingRow(t("loginLook.enter"), control = {
            Select(d.oneOf("enter", LOGIN_ENTERS, "zoom"), LOGIN_ENTERS.map { it to t("loginLook.enter.$it") }, { set("enter", it) }, label = t("loginLook.enter"))
        })
        SettingRow(t("loginLook.showStatus"), control = {
            CheckRow(d.bool("showStatus"), { set("showStatus", it) }, t("loginLook.showStatus"), desc = t("loginLook.showStatusHint"))
        })

        SettingRow(t("loginLook.texts"), desc = t("loginLook.textsHint"), id = "login-texts", startOpen = true) {
            for (locale in LOGIN_LOCALES) {
                val text = d.o("texts").o(locale)
                fun setText(field: String, value: String) = s.change(d.with("texts" to d.o("texts").with(locale to text.with(field to value))))
                Text(t("lang.$locale"), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
                SettingSub(t("loginLook.textTitle"), control = {
                    Field(text.str("title"), { v -> setText("title", v.replace(Regex("[\\p{Cc}  ]"), " ").take(LOGIN_TITLE_MAX)) }, t("loginLook.textTitle") + " – " + t("lang.$locale"), Modifier.fillMaxWidth(), placeholder = if (locale == "de") "Anmelden" else "Log in", singleLine = true)
                })
                SettingSub(t("loginLook.textNotice"), control = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Field(text.str("notice"), { v -> setText("notice", v.replace(Regex("[\\p{Cc}  ]"), " ").take(LOGIN_NOTICE_MAX)) }, t("loginLook.textNotice") + " – " + t("lang.$locale"), Modifier.fillMaxWidth(), singleLine = true)
                        Muted(t("loginLook.count", "count" to text.str("notice").codePointCount(0, text.str("notice").length), "max" to LOGIN_NOTICE_MAX))
                    }
                })
            }
        }
        Buttons {
            SmallButton(t("loginLook.reset"), { s.reset() })
            s.message?.let { (text, warn) -> Text(text, style = TitanType.rowSub, color = if (warn) Titan.colors.warnText else Titan.colors.text) }
        }
    }
}

private fun accentOf(d: JsonObject, account: JsonObject, p: TitanAppearance.Palette): Color {
    val src = if (d.str("theme", "inherit") == "inherit") account else d
    val id = src.str("accent", "blau")
    val pair = TitanAppearance.ACCENTS[id]
    val hex = if (id == "custom") src.str("customAccent").takeIf { HEX.matches(it) }?.let(TitanAppearance::parseHex) else null
    return Color(hex ?: if (p.dark) pair?.first ?: 0xFF6CB6FF else pair?.second ?: 0xFF1F6FC4)
}

/** Ein Regler in einer aufgeklappten Zeile (EffectSlider im Web). */
@Composable
private fun SubSlider(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    SettingSub(title, control = {
        SliderField(value.toFloat(), { f -> val v = f.toInt().coerceIn(range); if (v != value) onChange(v) }, range.first.toFloat()..range.last.toFloat(), title, value.toString())
    })
}

/** Ein Motiv der Galerie (static/backgrounds/<id>.webp) mit Namen. */
@Composable
private fun Motif(id: String, on: Boolean, onClick: () -> Unit) {
    val env = LocalEnv.current
    val image = rememberWebImage(env.api, "/backgrounds/$id.webp")
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(104.dp).clip(shape).border(if (on) 3.dp else 1.dp, if (on) Titan.colors.accent else Titan.colors.border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = if (on) "✓" else "" },
    ) {
        Box(Modifier.fillMaxWidth().height(58.dp).background(wallpaperBrush(Wall(false, id, 0, true), false))) {
            if (image != null) Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
        Text(t("dashboard.bg.$id"), style = TitanType.small, color = Titan.colors.text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

/** Kleines Bild der Kontenwahl: Kreise oder Liste. */
@Composable
private fun MiniPicker(circles: Boolean, on: Boolean) {
    val c = Titan.colors
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(44.dp, 28.dp).clip(shape).border(if (on) 2.dp else 1.dp, if (on) c.accent else c.border, shape).padding(5.dp), contentAlignment = Alignment.Center) {
        if (circles) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(c.muted)) } }
        else Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { repeat(2) { Box(Modifier.width(28.dp).height(5.dp).clip(RoundedCornerShape(2.dp)).background(c.muted)) } }
    }
}

/** Verkleinerte Vorschau der Anmeldeseite: Hintergrund, Abdunkeln, Karte mit Titel und Konten. */
@Composable
private fun LoginPreview(d: JsonObject, p: TitanAppearance.Palette, picture: ImageBitmap?, alpha: Float, hostname: String, accent: Color) {
    val tr = tr()
    val bg = d.o("background")
    val title = d.o("texts").o(tr.lang).str("title").ifEmpty { d.o("texts").o(if (tr.lang == "de") "en" else "de").str("title") }.ifEmpty { if (tr.de) "Anmelden" else "Log in" }
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(p.bg.color())
            .border(1.dp, Titan.colors.border, RoundedCornerShape(12.dp)).semantics { contentDescription = tr.t("loginLook.preview") },
    ) {
        if (picture == null && bg.str("source") == "builtin") Box(Modifier.matchParentSize().background(wallpaperBrush(Wall(false, bg.str("id"), 0, true), false)))
        if (picture != null) {
            val cover = bg.str("fit") != "contain"
            val zoom = if (cover) bg.int("zoom", 100) / 100f else 1f
            Image(
                picture, null,
                Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                contentScale = if (cover) ContentScale.Crop else ContentScale.Fit,
                alignment = BiasAlignment(bg.int("x", 50) / 50f - 1f, bg.int("y", 50) / 50f - 1f),
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = bg.int("dim", 30) / 100f)))
        }
        val wide = d.str("width") == "wide"
        val align = when (d.str("position")) { "left" -> Alignment.CenterStart; "right" -> Alignment.CenterEnd; else -> Alignment.Center }
        Column(
            Modifier.align(align).padding(horizontal = maxWidth * 0.06f).width(maxWidth * if (wide) 0.46f else 0.36f)
                .clip(RoundedCornerShape(12.dp)).background(p.surface.color().copy(alpha = alpha)).border(1.dp, p.border.color(), RoundedCornerShape(12.dp)).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!d.bool("hideLogo")) Box(Modifier.size(18.dp).clip(CircleShape).background(accent))
            Text(title, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = p.text.color())
            if (!d.bool("hideHost") && hostname.isNotEmpty()) Text(hostname, style = TitanType.small, color = p.muted.color())
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(p.chip.color()))
            Box(Modifier.fillMaxWidth(.6f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(accent))
        }
    }
}
