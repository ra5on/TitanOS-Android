package io.github.ra5on.titanos.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ra5on.titanos.model.OfficeIntent
import io.github.ra5on.titanos.net.Spki

/** Was eine Seite der Weboberfläche in der App braucht: Adresse, Sitzung der App, festgehaltene Zertifikate. */
data class WebSession(val base: String, val cookieName: String, val cookieToken: String, val pins: List<String>)

/**
 * Eine Seite der Weboberfläche des NAS in der App (etwa die Dateien mit dem
 * Office-Editor, ein Entwurf des Agenten), mit der Sitzung der App. Fremde
 * Zertifikate gelten nur, wenn sie das bei der Kopplung festgehaltene sind.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(session: WebSession, path: String, title: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHead(title, onClose)
        AndroidView(
            factory = { ctx ->
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie(session.base, "${session.cookieName}=${session.cookieToken}; Path=/; Secure")
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            val cert = error.certificate.x509Certificate
                            if (cert != null && session.pins.any { Spki.matches(cert, it) }) handler.proceed() else handler.cancel()
                        }
                    }
                    loadUrl(session.base.trimEnd('/') + path)
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Ist die TitanOS-App mit dem Office-Editor da (nimmt sie [OfficeIntent.ACTION] an)? */
fun officeApp(context: Context): Boolean =
    context.packageManager.queryIntentActivities(Intent(OfficeIntent.ACTION).setPackage(OfficeIntent.PACKAGE), 0).isNotEmpty()

/** Öffnet eine Datei („root/pfad“) oder eine Seite in der TitanOS-App; false, wenn sie es nicht kann. */
fun openInTitanOS(context: Context, path: String? = null, href: String? = null): Boolean {
    if (!officeApp(context)) return false
    val i = Intent(OfficeIntent.ACTION).setPackage(OfficeIntent.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (path != null) i.putExtra(OfficeIntent.EXTRA_PATH, path)
    if (href != null) i.putExtra(OfficeIntent.EXTRA_HREF, href)
    return runCatching { context.startActivity(i) }.isSuccess
}

