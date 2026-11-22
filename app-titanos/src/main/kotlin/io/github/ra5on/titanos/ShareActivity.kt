package io.github.ra5on.titanos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.IntentCompat
import io.github.ra5on.titanos.files.R as FilesR
import io.github.ra5on.titanos.files.ShareModel
import io.github.ra5on.titanos.files.ShareTargetScreen
import io.github.ra5on.titanos.net.IncomingUri
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.coroutines.launch

/**
 * „Teilen → Auf TitanOS speichern“ (Entwurf 80/81): nimmt eine oder mehrere
 * Dateien aus einer anderen App an, kopiert sie in den eigenen Speicher und
 * lädt sie im Hintergrund hoch. Ohne Kopplung sagt sie das und schließt.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as TitanApp).graph
        val api = graph.api
        val uris = incoming(intent)
        if (api == null || uris.isEmpty()) {
            if (api == null) Toast.makeText(this, getString(FilesR.string.share_not_paired), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val items = ShareModel.describe(this, uris)
        setContent {
            TitanTheme {
                val model = remember { ShareModel(this, api, items) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { model.loadRoots() }
                ShareTargetScreen(
                    model,
                    onCancel = { finish() },
                    onUpload = {
                        scope.launch {
                            val n = model.upload()
                            if (n > 0) {
                                Toast.makeText(this@ShareActivity, resources.getQuantityString(FilesR.plurals.share_started, n, n), Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    },
                    onOpen = { t -> scope.launch { model.open(t) } },
                )
            }
        }
    }

    /** Nur content:// fremder Apps: file:// und die eigenen Anbieter läse die App mit ihren eigenen Rechten. */
    private fun incoming(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.filter { IncomingUri.accepts(this, it) }
}
