package io.github.ra5on.titanos.net

import android.content.Context
import android.net.Uri

/**
 * Dateien, die andere Apps hereinreichen (Teilen, „Öffnen mit“, Ziehen):
 * nur `content://` fremder Anbieter. `file://` und die eigenen Anbieter würde
 * die App mit ihren eigenen Rechten lesen (oder beim Speichern schreiben),
 * also auch private Dateien der App.
 */
object IncomingUri {
    /**
     * [providerPackage] nennt das Paket, dem ein Anbieter gehört (null:
     * unbekannt). Eine vorangestellte Nutzerkennung (`10@authority`)
     * ignoriert Android beim Öffnen; sie zählt hier deshalb auch nicht.
     */
    fun accepts(scheme: String?, authority: String?, ownPackage: String, providerPackage: (String) -> String?): Boolean {
        if (scheme != "content") return false
        val a = authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
        val owner = providerPackage(a)
        if (owner == ownPackage) return false
        val looksOwn = a == ownPackage || a.startsWith("$ownPackage.")
        // Ein Anbieter einer anderen App mit ähnlichem Namen (etwa die Agent-App) gilt als fremd.
        return !looksOwn || (owner != null && owner != ownPackage)
    }

    fun accepts(context: Context, uri: Uri?): Boolean {
        uri ?: return false
        return accepts(uri.scheme, uri.authority, context.packageName) { a ->
            runCatching { context.packageManager.resolveContentProvider(a, 0)?.packageName }.getOrNull()
        }
    }
}
