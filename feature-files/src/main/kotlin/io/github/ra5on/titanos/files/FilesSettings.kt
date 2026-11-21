package io.github.ra5on.titanos.files

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.TitanIcons
import java.io.File

/** Wann etwas über das Netz geladen wird. */
enum class NetPolicy { Always, WifiOnly, Never }

/**
 * Einstellungen des Dateibereichs nur für dieses Handy (Inhaber: „so viele
 * Einstellmöglichkeiten wie möglich“, gute Vorgaben). Was das Konto im Web
 * auch hat (Ansicht, Sortierung, versteckte Dateien, Favoriten), liegt in
 * `preferences.files` am NAS (FilePrefs).
 */
data class FilesSettings(
    val thumbs: NetPolicy = NetPolicy.Always,
    val originals: NetPolicy = NetPolicy.Always,
    val officeDefault: Boolean = true,
    val pdfInOffice: Boolean = false,
    val textOpensEditor: Boolean = false,
    val autoplay: Boolean = true,
    val confirmTrash: Boolean = true,
    val showUndo: Boolean = true,
    val uploadConflict: String = "rename",
    val cacheMb: Int = 512,
    val backgroundAudio: Boolean = true,
    val folderPlaylist: Boolean = true,
    val previewPane: Boolean = true,
    val sidebar: Boolean = true,
    val rightClickMenu: Boolean = true,
    val dragAndDrop: Boolean = true,
) {
    companion object {
        @Volatile private var cached: FilesSettings? = null
        private var state = mutableStateOf(FilesSettings())

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("files-settings", Context.MODE_PRIVATE)

        /** Die aktuellen Einstellungen; in Compose beobachtbar. */
        fun get(c: Context): FilesSettings {
            cached?.let { return state.value }
            val p = prefs(c)
            val d = FilesSettings()
            fun policy(k: String, def: NetPolicy) = NetPolicy.entries.firstOrNull { it.name == p.getString(k, null) } ?: def
            val s = FilesSettings(
                thumbs = policy("thumbs", d.thumbs),
                originals = policy("originals", d.originals),
                officeDefault = p.getBoolean("officeDefault", d.officeDefault),
                pdfInOffice = p.getBoolean("pdfInOffice", d.pdfInOffice),
                textOpensEditor = p.getBoolean("textOpensEditor", d.textOpensEditor),
                autoplay = p.getBoolean("autoplay", d.autoplay),
                confirmTrash = p.getBoolean("confirmTrash", d.confirmTrash),
                showUndo = p.getBoolean("showUndo", d.showUndo),
                uploadConflict = p.getString("uploadConflict", d.uploadConflict) ?: d.uploadConflict,
                cacheMb = p.getInt("cacheMb", d.cacheMb),
                backgroundAudio = p.getBoolean("backgroundAudio", d.backgroundAudio),
                folderPlaylist = p.getBoolean("folderPlaylist", d.folderPlaylist),
                previewPane = p.getBoolean("previewPane", d.previewPane),
                sidebar = p.getBoolean("sidebar", d.sidebar),
                rightClickMenu = p.getBoolean("rightClickMenu", d.rightClickMenu),
                dragAndDrop = p.getBoolean("dragAndDrop", d.dragAndDrop),
            )
            cached = s
            state.value = s
            return s
        }

        fun save(c: Context, s: FilesSettings) {
            prefs(c).edit {
                putString("thumbs", s.thumbs.name)
                putString("originals", s.originals.name)
                putBoolean("officeDefault", s.officeDefault)
                putBoolean("pdfInOffice", s.pdfInOffice)
                putBoolean("textOpensEditor", s.textOpensEditor)
                putBoolean("autoplay", s.autoplay)
                putBoolean("confirmTrash", s.confirmTrash)
                putBoolean("showUndo", s.showUndo)
                putString("uploadConflict", s.uploadConflict)
                putInt("cacheMb", s.cacheMb)
                putBoolean("backgroundAudio", s.backgroundAudio)
                putBoolean("folderPlaylist", s.folderPlaylist)
                putBoolean("previewPane", s.previewPane)
                putBoolean("sidebar", s.sidebar)
                putBoolean("rightClickMenu", s.rightClickMenu)
                putBoolean("dragAndDrop", s.dragAndDrop)
            }
            cached = s
            state.value = s
        }

        /** Ob ein Ladevorgang nach [policy] gerade erlaubt ist (WLAN = nicht getaktet). */
        fun allowed(c: Context, policy: NetPolicy): Boolean = when (policy) {
            NetPolicy.Always -> true
            NetPolicy.Never -> false
            NetPolicy.WifiOnly -> {
                val cm = c.getSystemService(ConnectivityManager::class.java)
                cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            }
        }

        fun cacheBytes(c: Context): Long = File(c.cacheDir, "view").listFiles()?.sumOf { it.length() } ?: 0L
    }
}

/** Einstellungen des Dateibereichs in aufklappbaren Gruppen. */
@Composable
fun FilesSettingsScreen(settings: FilesSettings, cacheBytes: Long, onChange: (FilesSettings) -> Unit, onClearCache: () -> Unit, onBack: () -> Unit) {
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    var open by rememberSaveable { mutableStateOf(listOf("view")) }
    fun toggle(k: String) { open = if (k in open) open - k else open + k }
    @Composable
    fun sw(title: Int, sub: Int?, value: Boolean, divider: Boolean, set: (Boolean) -> Unit) = ListRow(
        stringResource(title), sub = sub?.let { stringResource(it) }, right = { Toggle(value, set, on, off) }, divider = divider, onClick = { set(!value) },
    )
    @Composable
    fun policyRows(value: NetPolicy, set: (NetPolicy) -> Unit) = NetPolicy.entries.forEachIndexed { i, p ->
        ListRow(
            stringResource(when (p) { NetPolicy.Always -> R.string.fs_always; NetPolicy.WifiOnly -> R.string.fs_wifi; NetPolicy.Never -> R.string.fs_never }),
            icon = { RadioMark(p == value) }, divider = i > 0, onClick = { set(p) },
        )
    }
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.fs_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        Spacer(Modifier.height(4.dp))
        Group(stringResource(R.string.fs_thumbs), stringResource(R.string.fs_thumbs_text), TitanIcons.image, "view" in open, { toggle("view") }) {
            policyRows(settings.thumbs) { onChange(settings.copy(thumbs = it)) }
        }
        Group(stringResource(R.string.fs_originals), stringResource(R.string.fs_originals_text), TitanIcons.photo, "orig" in open, { toggle("orig") }) {
            policyRows(settings.originals) { onChange(settings.copy(originals = it)) }
        }
        Group(stringResource(R.string.fs_open), null, TitanIcons.file, "open" in open, { toggle("open") }) {
            sw(R.string.fs_office, R.string.fs_office_text, settings.officeDefault, false) { onChange(settings.copy(officeDefault = it)) }
            sw(R.string.fs_pdf, R.string.fs_pdf_text, settings.pdfInOffice, true) { onChange(settings.copy(pdfInOffice = it)) }
            sw(R.string.fs_text, R.string.fs_text_text, settings.textOpensEditor, true) { onChange(settings.copy(textOpensEditor = it)) }
        }
        Group(stringResource(R.string.fs_media), null, TitanIcons.play, "media" in open, { toggle("media") }) {
            sw(R.string.fs_autoplay, null, settings.autoplay, false) { onChange(settings.copy(autoplay = it)) }
            sw(R.string.fs_background, R.string.fs_background_text, settings.backgroundAudio, true) { onChange(settings.copy(backgroundAudio = it)) }
            sw(R.string.fs_playlist, R.string.fs_playlist_text, settings.folderPlaylist, true) { onChange(settings.copy(folderPlaylist = it)) }
        }
        Group(stringResource(R.string.fs_delete), null, TitanIcons.trash, "delete" in open, { toggle("delete") }) {
            sw(R.string.fs_confirm, R.string.fs_confirm_text, settings.confirmTrash, false) { onChange(settings.copy(confirmTrash = it)) }
            sw(R.string.fs_undo, null, settings.showUndo, true) { onChange(settings.copy(showUndo = it)) }
        }
        Group(stringResource(R.string.fs_upload), null, TitanIcons.upload, "upload" in open, { toggle("upload") }) {
            listOf("rename" to R.string.paste_rename, "replace" to R.string.paste_replace, "" to R.string.fs_upload_fail).forEachIndexed { i, (k, label) ->
                ListRow(stringResource(label), icon = { RadioMark(settings.uploadConflict == k) }, divider = i > 0, onClick = { onChange(settings.copy(uploadConflict = k)) })
            }
        }
        Group(stringResource(R.string.fs_screen), stringResource(R.string.fs_screen_text), TitanIcons.monitor, "screen" in open, { toggle("screen") }) {
            sw(R.string.fs_pane, R.string.fs_pane_text, settings.previewPane, false) { onChange(settings.copy(previewPane = it)) }
            sw(R.string.fs_sidebar, R.string.fs_sidebar_text, settings.sidebar, true) { onChange(settings.copy(sidebar = it)) }
            sw(R.string.fs_rightclick, null, settings.rightClickMenu, true) { onChange(settings.copy(rightClickMenu = it)) }
            sw(R.string.fs_drag, R.string.fs_drag_text, settings.dragAndDrop, true) { onChange(settings.copy(dragAndDrop = it)) }
        }
        Group(stringResource(R.string.fs_cache), stringResource(R.string.fs_cache_used, Format.bytes(cacheBytes)), TitanIcons.hdd, "cache" in open, { toggle("cache") }) {
            listOf(256, 512, 1024, 2048, 4096).forEachIndexed { i, mb ->
                ListRow(Format.bytes(mb * 1024L * 1024), icon = { RadioMark(settings.cacheMb == mb) }, divider = i > 0, onClick = { onChange(settings.copy(cacheMb = mb)) })
            }
            Box(Modifier.padding(14.dp)) { TitanButton(stringResource(R.string.fs_cache_clear), onClearCache) }
        }
        Note(stringResource(R.string.fs_note), Modifier.padding(top = 14.dp))
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@Composable
private fun Group(
    title: String,
    summary: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = io.github.ra5on.titanos.ui.theme.Titan.colors
    val state = stringResource(if (open) R.string.fs_group_open else R.string.fs_group_closed)
    io.github.ra5on.titanos.ui.components.Card(Modifier.padding(top = 10.dp)) {
        ListRow(
            title,
            modifier = Modifier.semantics { stateDescription = state },
            sub = summary,
            icon = { io.github.ra5on.titanos.ui.components.RowIcon(icon, size = 24.dp) },
            right = {
                androidx.compose.material3.Icon(
                    TitanIcons.down, null, tint = c.muted,
                    modifier = Modifier.padding(2.dp).rotate(if (open) 180f else 0f),
                )
            },
            onClick = onToggle,
        )
        if (open) {
            io.github.ra5on.titanos.ui.components.Divider()
            content()
        }
    }
}
