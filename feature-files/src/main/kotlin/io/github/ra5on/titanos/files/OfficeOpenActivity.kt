package io.github.ra5on.titanos.files

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanTheme
import io.github.ra5on.titanos.ui.theme.TitanType

/**
 * „Öffnen mit TitanOS“ für Word, Excel, PowerPoint, PDF und OpenDocument aus
 * anderen Apps (Inhaber 21.11.2026): die Datei öffnet in T-Word, T-Exel,
 * T-Deck oder T-PDF vom NAS. Gespeichert wird zurück in die Datei, wenn die
 * andere App Schreiben erlaubt; sonst landet das Ergebnis in Downloads.
 */
class OfficeOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val api = FilesRuntime.api()
        val store = FilesRuntime.store
        val offline = FilesRuntime.offline
        val name = uri?.let { u ->
            runCatching { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                ?: u.lastPathSegment?.substringAfterLast('/')
        } ?: "Dokument"
        val writable = intent?.action == Intent.ACTION_EDIT || (intent?.flags ?: 0) and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        val model = if (api != null && store != null && offline != null) FilesModel(applicationContext, api, store, lifecycleScope, offline) else null
        setContent {
            TitanTheme {
                var app by remember { mutableStateOf<WebApp?>(null) }
                var problem by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(Unit) {
                    if (model == null || uri == null) {
                        problem = if (model == null) R.string.office_not_paired else R.string.office_no_file
                        return@LaunchedEffect
                    }
                    val apps = runCatching { model.files?.webapps().orEmpty() }.getOrDefault(emptyList())
                    val prefs = runCatching { model.files?.fileApps().orEmpty() }.getOrDefault(emptyMap())
                    app = OfficeChoice.any(name, apps, prefs)
                    if (app == null) problem = R.string.office_no_app
                }
                val a = app
                if (a != null && model != null && uri != null) {
                    OfficeScreen(model, a, Location(FileRoot("", "")), name, onClose = { finish() }, local = LocalDoc(uri, name, writable))
                } else {
                    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(name, onBack = { finish() }, backLabel = stringResource(UiR.string.ui_back)) } }) {
                        val p = problem
                        if (p == null) {
                            Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(top = 16.dp))
                        } else {
                            Problem(stringResource(p), Modifier.padding(top = 16.dp))
                            TitanButton(stringResource(UiR.string.ui_close), { finish() }, Modifier.fillMaxWidth().padding(top = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
