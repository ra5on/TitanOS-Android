package io.github.ra5on.titanos.files

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.ui.theme.TitanTheme

/**
 * Ein weiteres Fenster der App mit einem Ordner oder einer Datei
 * („In neuem Fenster öffnen“, Inhaber 21.11.2026). Jedes Fenster hat eigene
 * Seiten und eigene Auswahl; Übertragungen, Offline und Einstellungen sind
 * dieselben wie im Hauptfenster.
 */
class FilesWindowActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val api = FilesRuntime.api()
        val store = FilesRuntime.store
        val offline = FilesRuntime.offline
        if (api == null || store == null || offline == null) {
            finish()
            return
        }
        val model = FilesModel(applicationContext, api, store, lifecycleScope, offline)
        val root = intent.getStringExtra(FilesWindow.EXTRA_ROOT)
        val path = intent.getStringExtra(FilesWindow.EXTRA_PATH).orEmpty()
        val name = intent.getStringExtra(FilesWindow.EXTRA_NAME)
        model.onLeaveRoots = { finish() }
        setContent {
            TitanTheme {
                var started by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    model.loadRoots()
                    val r = model.roots.roots?.firstOrNull { it.id == root }
                    if (r == null) {
                        finish()
                        return@LaunchedEffect
                    }
                    val loc = Location(r, path)
                    model.openFolder(loc)
                    if (name != null) {
                        model.open(loc)
                        model.reload()
                        val entries = model.sorted(loc, model.folder?.entries.orEmpty()).filter { !it.dir }
                        val i = entries.indexOfFirst { it.name == name }
                        val e = entries.getOrNull(i) ?: FileEntry(name)
                        model.loadOffice()
                        val office = if (Kinds.of(name) == FileKind.Office) OfficeChoice.any(name, model.files?.webapps().orEmpty(), emptyMap()) else null
                        if (office != null) model.push(FilesRoute.Office(loc, name, office))
                        else model.push(FilesRoute.Viewer(loc, if (i >= 0) entries else listOf(e), i.coerceAtLeast(0)))
                    }
                    started = true
                }
                if (started) FilesTab(model) {}
            }
        }
    }
}
