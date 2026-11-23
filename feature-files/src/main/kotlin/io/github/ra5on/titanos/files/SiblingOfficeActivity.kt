package io.github.ra5on.titanos.files

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.ra5on.titanos.model.OfficeIntent

/**
 * Aus der Agent-App (Aktion [OfficeIntent.ACTION], nur mit der
 * Signatur-Berechtigung SIBLING): eine Datei des NAS im eingebetteten Office
 * öffnen. `path` ist „<Ablage>/<Pfad>“ wie im Dateimanager, etwa
 * „share/Documents/Brief.docx“. Geöffnet wird in einem eigenen Fenster der
 * Dateien (Office für Word/Excel/PowerPoint, sonst der Betrachter).
 */
class SiblingOfficeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.getStringExtra(OfficeIntent.EXTRA_PATH)?.let(::splitNasPath)
        if (target == null) {
            if (intent?.hasExtra(OfficeIntent.EXTRA_HREF) == true) Toast.makeText(this, R.string.sibling_href_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val (root, dir, name) = target
        startActivity(
            Intent(this, FilesWindowActivity::class.java)
                .putExtra(FilesWindow.EXTRA_ROOT, root)
                .putExtra(FilesWindow.EXTRA_PATH, dir)
                .putExtra(FilesWindow.EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
        finish()
    }
}

/**
 * „share/Documents/Ordner/Brief.docx“ → (share/Documents, Ordner, Brief.docx).
 * Ablagen haben zwei Glieder (share|volume|usb/<Name>), der Systemstick eines.
 */
fun splitNasPath(raw: String): Triple<String, String, String>? {
    val parts = raw.trim().trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.any { it == ".." || it == "." }) return null
    val rootParts = when (parts.firstOrNull()) {
        "share", "volume", "usb" -> 2
        "stick" -> 1
        else -> return null
    }
    if (parts.size <= rootParts) return null
    val root = parts.take(rootParts).joinToString("/")
    val rest = parts.drop(rootParts)
    return Triple(root, rest.dropLast(1).joinToString("/"), rest.last())
}
