package io.github.ra5on.titanos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.ra5on.titanos.home.programLabelRes
import io.github.ra5on.titanos.photos.PhotoScheduler
import io.github.ra5on.titanos.photos.PhotosRuntime
import io.github.ra5on.titanos.widgets.AgentWidget
import io.github.ra5on.titanos.widgets.PhotosWidget

/**
 * Kurzbefehle am App-Symbol (lange drücken): fest „Agent fragen“, „Hochladen“
 * und „Fotos sichern“ (res/xml/shortcuts.xml), dazu die zuletzt geöffneten
 * Programme der Startseite als dynamische Kurzbefehle.
 */
object Shortcuts {
    const val ACTION = "io.github.ra5on.titanos.SHORTCUT"
    const val EXTRA = "shortcut"
    const val EXTRA_PROGRAM = "program"

    /** Ein Programm der Startseite wurde geöffnet: vorn in die Liste der Kurzbefehle (Android hält nur wenige). */
    fun opened(context: Context, id: String) {
        val label = context.getString(programLabelRes(id))
        val info = ShortcutInfoCompat.Builder(context, "p-$id")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_sc_program_round))
            .setIntent(Intent(context, ShortcutActivity::class.java).setAction(ACTION).putExtra(EXTRA, EXTRA_PROGRAM).putExtra(EXTRA_PROGRAM, id))
            .setRank(0)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info) }
    }

    /** Kopplung weg: zuletzt geöffnete Programme gehören zum NAS, das es nicht mehr gibt. */
    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    /** Den Agenten fragen: die Agent-App mit ihrer Schnittstelle (ASK), wie das Widget. */
    fun askAgent(context: Context): Intent? {
        val installed = runCatching { context.packageManager.getPackageInfo(AgentWidget.AGENT_PACKAGE, 0) }.isSuccess
        if (!installed) return null
        return Intent(AgentWidget.ASK_ACTION).setPackage(AgentWidget.AGENT_PACKAGE).putExtra("voice", false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** „Fotos sichern“ an alle NAS mit eingeschalteter Sicherung; falsch, wenn Android ablehnt oder keine eingeschaltet ist. */
    fun backupNow(context: Context): Boolean {
        val nas = PhotosRuntime.enabledNas(context)
        if (nas.isEmpty()) return false
        val ok = PhotoScheduler.backupNow(context, nas, 0)
        // Abgelehnt: die normalen Aufträge mit den eingestellten Bedingungen laufen trotzdem.
        if (!ok) PhotoScheduler.armAll(context)
        return ok
    }
}

/**
 * Ziel der Kurzbefehle; ohne eigene Oberfläche. „Hochladen“ öffnet die
 * Dateiauswahl des Systems und gibt die Dateien an „Auf TitanOS speichern“
 * weiter (dort wählt man Freigabe und Ordner).
 */
class ShortcutActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> forward(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val graph = (application as TitanApp).graph
        val what = intent?.getStringExtra(Shortcuts.EXTRA)
        if (graph.pairing == null && what != "ask") {
            Toast.makeText(this, getString(R.string.sc_not_paired), Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        when (what) {
            "ask" -> {
                val ask = Shortcuts.askAgent(this)
                if (ask != null) {
                    startActivity(ask)
                } else {
                    Toast.makeText(this, getString(R.string.sc_agent_missing), Toast.LENGTH_LONG).show()
                    startActivity(appIntent().putExtra(AgentWidget.EXTRA_OPEN_ABOUT, true))
                }
                finish()
            }
            "upload" -> pick.launch(arrayOf("*/*"))
            "photos" -> {
                val started = Shortcuts.backupNow(this)
                Toast.makeText(this, getString(if (started) R.string.tile_photos_started else R.string.tile_photos_refused), Toast.LENGTH_SHORT).show()
                startActivity(appIntent().putExtra(PhotosWidget.EXTRA_OPEN_PHOTOS, true))
                finish()
            }
            Shortcuts.EXTRA_PROGRAM -> {
                startActivity(appIntent().putExtra(Shortcuts.EXTRA_PROGRAM, intent.getStringExtra(Shortcuts.EXTRA_PROGRAM)))
                finish()
            }
            else -> openApp()
        }
    }

    private fun appIntent(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun openApp() {
        startActivity(appIntent())
        finish()
    }

    private fun forward(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            val send = Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(send)
        }
        finish()
    }
}
