package io.github.ra5on.titanos.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/** Für `shouldInterceptRequest`: GET/HEAD an den eigenen Ursprung über den gepinnten Client, sonst null. */
fun PinnedWebProxy.intercept(request: WebResourceRequest): WebResourceResponse? =
    fetch(request.method, request.url.toString(), request.requestHeaders.orEmpty())?.toWebResponse()

fun ProxiedResponse.toWebResponse(): WebResourceResponse =
    WebResourceResponse(mimeType, charset, status, reason, headers, body)

/** Cookies der WebView für den Proxy (lesen und Set-Cookie zurückschreiben). */
object WebViewCookies {
    fun read(url: String): String? = CookieManager.getInstance().getCookie(url)

    fun write(url: String, value: String) {
        CookieManager.getInstance().setCookie(url, value)
    }
}

/** Öffnet eine fremde http(s)-Seite im Browser; nie `intent:` oder andere Schemata. */
fun openExternalWeb(context: Context, url: Uri): Boolean {
    if (url.scheme != "https" && url.scheme != "http") return false
    val i = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(i)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * WebViewClient für Seiten des NAS mit der Sitzung der App:
 * - Navigation nur im eigenen Ursprung, fremde http(s)-Links im Browser,
 *   andere Schemata gar nicht;
 * - GET/HEAD an den eigenen Ursprung über [proxy] (gepinnter Client);
 * - Anfragen mit Inhalt sendet die WebView selbst, vorher prüft
 *   [PinnedOrigin.verify] die Adresse über eine frische gepinnte Verbindung;
 * - ein selbst signiertes Zertifikat gilt nur mit dem Pin aus der Kopplung.
 */
open class PinnedWebViewClient(
    protected val context: Context,
    protected val origin: PinnedOrigin,
    protected val proxy: PinnedWebProxy,
    private val onBadCert: () -> Unit = {},
) : android.webkit.WebViewClient() {

    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: WebResourceRequest): Boolean =
        when (WebNav.of(request.url.toString(), origin)) {
            WebNav.Allow -> false
            WebNav.External -> {
                if (request.isForMainFrame) openExternalWeb(context, request.url)
                true
            }
            WebNav.Block -> true
        }

    override fun shouldInterceptRequest(view: android.webkit.WebView, request: WebResourceRequest): WebResourceResponse? = route(request)

    /** Auch für Anfragen eines Service Workers (die gehen nicht über den WebViewClient). */
    open fun route(request: WebResourceRequest): WebResourceResponse? {
        if (!origin.owns(request.url.toString())) return null
        proxy.intercept(request)?.let { return it }
        return if (origin.verify()) null else PinnedWebProxy.blocked().toWebResponse()
    }

    @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: android.webkit.WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
        val cert = error.certificate?.x509Certificate
        if (origin.owns(error.url) && cert != null && Spki.matches(cert, origin.spki)) {
            handler.proceed()
        } else {
            handler.cancel()
            onBadCert()
        }
    }

    /** Leitet die Anfragen von Service Workern (prozessweit) über [route]. */
    fun routeServiceWorkers() {
        android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = route(request)
        })
    }
}
