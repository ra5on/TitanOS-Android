package io.github.ra5on.titanos.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ra5on.titanos.model.OfficeIntent
import io.github.ra5on.titanos.net.PinnedOrigin
import io.github.ra5on.titanos.net.PinnedWebProxy
import io.github.ra5on.titanos.net.PinnedWebViewClient
import io.github.ra5on.titanos.net.WebCookie
import io.github.ra5on.titanos.net.WebViewCookies

/**
 * Was eine Seite der Weboberfläche in der App braucht: den über die gepinnte
 * Verbindung gewählten Ursprung des NAS (Adresse, Client, Pin) und die
 * Sitzung der App.
 */
class WebSession(val origin: PinnedOrigin, val cookieName: String, val cookieToken: String)

/**
 * Eine Seite der Weboberfläche des NAS in der App (etwa die Dateien mit dem
 * Office-Editor, ein Entwurf des Agenten), mit der Sitzung der App. Seiten
 * und Dateien (GET) holt die App über den gepinnten Client; Navigation bleibt
 * im eigenen Ursprung, fremde Links öffnet der Browser (PinnedWebViewClient).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(session: WebSession, path: String, title: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHead(title, onClose)
        AndroidView(
            factory = { ctx ->
                val origin = session.origin
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie(origin.origin, WebCookie.session(session.cookieName, session.cookieToken))
                cm.flush()
                val proxy = PinnedWebProxy(origin, cookies = WebViewCookies::read, cookieSink = WebViewCookies::write)
                val client = PinnedWebViewClient(ctx, origin, proxy)
                // Die Weboberfläche meldet einen Service Worker an; dessen Anfragen gehen nicht über den WebViewClient.
                client.routeServiceWorkers()
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = client
                    loadUrl(origin.origin + path)
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

