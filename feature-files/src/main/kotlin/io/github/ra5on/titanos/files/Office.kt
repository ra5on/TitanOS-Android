package io.github.ra5on.titanos.files

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.net.Spki
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.security.cert.X509Certificate

/** Welches Office-Programm eine Datei öffnet (wie chosenApp in protocol.ts). */
object OfficeChoice {
    fun appsFor(ext: String, apps: List<WebApp>): List<WebApp> = apps.filter { it.ready && ext in it.types }

    /** Die Wahl des Kontos, sonst die Vorgabe des Katalogs, sonst das erste passende Programm; null: TitanOS selbst. */
    fun chosen(name: String, apps: List<WebApp>, prefs: Map<String, String>, titanosShows: Boolean): WebApp? {
        val ext = Kinds.ext(name)
        if (ext.isEmpty()) return null
        val fit = appsFor(ext, apps)
        val own = prefs[ext]
        if (own == "editor") return null
        fit.firstOrNull { "webapp:${it.id}" == own }?.let { return it }
        return fit.firstOrNull { it.isDefault } ?: if (titanosShows) null else fit.firstOrNull()
    }

    /** Für „In … öffnen“: auch wenn die App die Datei selbst zeigt (PDF, Text). */
    fun any(name: String, apps: List<WebApp>, prefs: Map<String, String>): WebApp? =
        chosen(name, apps, prefs, titanosShows = false)
}

/** Wo die WebView das NAS erreicht: Adresse, ihr Pin und ob ein öffentliches Zertifikat gilt. */
data class OfficeHost(val base: HttpUrl, val spki: String, val allowPublic: Boolean) {
    val origin: String get() = base.toString().trimEnd('/')

    fun accepts(cert: X509Certificate?): Boolean = cert != null && Spki.matches(cert, spki)

    companion object {
        /** Die erste Adresse, die antwortet; das Heimnetz zuerst (wie NasConnection). */
        suspend fun resolve(access: FilesAccess): OfficeHost? = withContext(Dispatchers.IO) {
            val ordered = access.pairing.addresses.sortedBy { if (it.address?.kind == AddressKind.LAN) 0 else 1 }
            for (pa in ordered) {
                val endpoint = Endpoint.pinned(access.shared, pa) ?: continue
                val ok = runCatching {
                    endpoint.client.newCall(Request.Builder().url(endpoint.base.newBuilder().encodedPath("/api/v1/session").build()).build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) return@withContext host(endpoint.base, pa)
            }
            null
        }

        fun host(base: HttpUrl, pa: PinnedAddress) = OfficeHost(base, pa.spki, pa.publicCert || pa.address?.hasPublicName == true)
    }
}

/** Eine Datei aus einer anderen App („Öffnen mit“), nicht vom NAS. */
data class LocalDoc(val uri: Uri, val name: String, val writable: Boolean)

/** Eine offene Frage der Seite an die App. */
data class OfficeAsk(val kind: String, val name: String, val id: Int)

private const val HOST_PATH = "/__titanos_app/office.html"
private val ALLOWED_API = listOf("/api/v1/files/", "/api/v1/webapps", "/api/v1/preferences", "/api/v1/session")

/**
 * Ein Office-Programm von TitanOS (T-Word, T-Exel, T-PDF, T-Deck …) in einer
 * WebView mit der Sitzung der App. Nur die Seiten des NAS unter /webapps/ und
 * die Dateirouten; das Zertifikat wird wie in core-net am SPKI-Pin geprüft.
 * Die Rahmenseite (assets/titanos-office.html) liegt unter dem Ursprung des
 * NAS, damit das Programm wie im Web eingebettet läuft.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun OfficeScreen(model: FilesModel, app: WebApp, location: Location, name: String?, onClose: () -> Unit, local: LocalDoc? = null) {
    val access = model.access ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf<OfficeHost?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var askClose by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<OfficeAsk?>(null) }
    var askName by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(name) }
    var notice by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val texts = OfficeTexts(
        certificate = stringResource(R.string.office_certificate),
        unreachable = stringResource(R.string.office_unreachable),
        saved = stringResource(R.string.office_saved),
        keptOnPhone = stringResource(R.string.office_kept_phone),
        copyWord = stringResource(R.string.office_copy_word),
    )
    val errorText = stringResource(R.string.office_error)
    fun answer(id: Int, value: String?) {
        val js = "window.titanAnswer($id, ${if (value == null) "null" else JsonPrimitive(value)})"
        web?.evaluateJavascript(js, null)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { readPicked(context, it) } }
            val json = buildJsonArray { files.forEach { (n, b) -> add(buildJsonObject { put("name", n); put("data", Base64.encodeToString(b, Base64.NO_WRAP)) }) } }
            web?.evaluateJavascript("window.titanPicked($json)", null)
        }
    }
    LaunchedEffect(Unit) {
        try {
            model.files?.webapps() // sorgt für eine frische Sitzung
            host = OfficeHost.resolve(access) ?: run { failed = texts.unreachable; null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = texts.unreachable
        }
    }
    val close = { if (dirty) askClose = true else onClose() }
    BackHandler(onBack = close)
    val c = Titan.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.statusBarsPadding()) {
                TopBar(
                    current ?: app.name,
                    onBack = close,
                    backLabel = stringResource(UiR.string.ui_back),
                    actions = {
                        Text(app.name, style = TitanType.small, color = c.muted)
                        IconButton(TitanIcons.check, stringResource(R.string.edit_save), { web?.evaluateJavascript("window.titanCommand('save')", null) })
                        IconButton(TitanIcons.download, stringResource(R.string.office_save_as), { web?.evaluateJavascript("window.titanCommand('save-as')", null) })
                    },
                )
            }
            failed?.let { Problem(it, Modifier.padding(16.dp)) }
            notice?.let { Text(it, style = TitanType.small, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            val h = host
            if (h != null && failed == null) {
                val bridge = remember(h) {
                    OfficeBridge(
                        config = officeConfig(app, location, name, model.userName.orEmpty(), texts.copyWord, local),
                        readLocal = { local?.let { l -> Base64.encodeToString(context.contentResolver.openInputStream(l.uri)!!.let { FilesApi.readLimited(it, LOCAL_LIMIT) }, Base64.NO_WRAP) }.orEmpty() },
                        writeLocal = { b64 ->
                            val l = local
                            if (l == null || !l.writable) "readonly"
                            else runCatching { context.contentResolver.openOutputStream(l.uri, "wt")!!.use { it.write(Base64.decode(b64, Base64.DEFAULT)) } }.fold({ "ok" }, { "error" })
                        },
                        renew = { runBlocking { runCatching { model.files?.webapps() } }; setCookie(access, h) },
                        onAsk = { a -> main.post { askName = a.name; ask = a } },
                        onDirty = { d -> main.post { dirty = d } },
                        onError = { kind, code -> main.post { notice = String.format(errorText, kind, code) } },
                        onSaved = { path -> main.post { notice = texts.saved; current = path.substringAfterLast('/'); dirty = false; scope.launch { model.reload() } } },
                        onPick = { accept, _ -> main.post { pick.launch(acceptTypes(accept)) } },
                        onKeep = { n, b64 ->
                            val ok = runCatching { keepOnPhone(context, n, Base64.decode(b64, Base64.DEFAULT)) }.isSuccess
                            main.post { notice = if (ok) texts.keptOnPhone else String.format(errorText, "phone", "") }
                        },
                    )
                }
                AndroidView(
                    factory = { ctx ->
                        setCookie(access, h)
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            addJavascriptInterface(bridge, "TitanOffice")
                            webViewClient = OfficeClient(ctx, h) { failed = texts.certificate; stopLoading() }
                            loadUrl(h.origin + HOST_PATH)
                            web = this
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
                )
                DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }
            } else if (failed == null) {
                Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(16.dp))
            } else {
                TitanButton(stringResource(UiR.string.ui_close), onClose, Modifier.padding(16.dp), kind = ButtonKind.Normal)
            }
        }
        if (askClose) {
            ConfirmDialog(
                title = stringResource(R.string.edit_discard_title),
                text = stringResource(R.string.office_discard_text),
                actions = listOf(
                    DialogAction(stringResource(R.string.edit_discard), danger = true) { askClose = false; onClose() },
                    DialogAction(stringResource(R.string.edit_continue)) { askClose = false },
                ),
                onDismiss = { askClose = false },
            )
        }
        ask?.let { a ->
            val done = { v: String? -> ask = null; answer(a.id, v) }
            when (a.kind) {
                "conflict" -> ConfirmDialog(
                    title = stringResource(R.string.office_conflict_title),
                    text = stringResource(R.string.office_conflict_text, a.name),
                    actions = listOf(
                        DialogAction(stringResource(R.string.edit_overwrite), danger = true) { done("overwrite") },
                        DialogAction(stringResource(R.string.office_save_copy)) { done("copy") },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { done(null) },
                    ),
                    onDismiss = { done(null) },
                )
                "created" -> ConfirmDialog(
                    title = stringResource(R.string.office_created_title, a.name),
                    text = stringResource(R.string.office_created_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.office_keep_nas)) { done("nas") },
                        DialogAction(stringResource(R.string.office_keep_phone)) { done("phone") },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { done(null) },
                    ),
                    onDismiss = { done(null) },
                )
                "exists" -> ConfirmDialog(
                    title = stringResource(R.string.office_exists_title, a.name),
                    text = stringResource(R.string.office_exists_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.paste_replace), danger = true) { done("replace") },
                        DialogAction(stringResource(R.string.office_other_name)) { done("name:" + otherName(askName)) },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { done(null) },
                    ),
                    onDismiss = { done(null) },
                )
                else -> ConfirmDialog(
                    title = stringResource(R.string.office_save_as),
                    text = stringResource(R.string.office_save_as_text, location.place),
                    actions = listOf(
                        DialogAction(stringResource(UiR.string.ui_cancel)) { done(null) },
                        DialogAction(stringResource(R.string.edit_save)) { if (askName.isNotBlank() && '/' !in askName) done(askName.trim()) },
                    ),
                    onDismiss = { done(null) },
                ) {
                    TextInput(askName, { askName = it }, stringResource(R.string.url_name), placeholder = a.name)
                    Box(Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
}

/** „Angebot.docx“ → „Angebot (2).docx“ für „Anderer Name“. */
internal fun otherName(name: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    val m = Regex("^(.*) \\((\\d+)\\)$").find(stem)
    return if (m != null) "${m.groupValues[1]} (${m.groupValues[2].toInt() + 1})$ext" else "$stem (2)$ext"
}

private data class OfficeTexts(val certificate: String, val unreachable: String, val saved: String, val keptOnPhone: String, val copyWord: String)

/** Was die Rahmenseite beim Start bekommt (wie appSrc in protocol.ts). */
internal fun officeConfig(app: WebApp, location: Location, name: String?, author: String, copyWord: String, local: LocalDoc? = null): String {
    val src = app.url!!.trimEnd('/') + "/?" + listOf("author" to author, "bridge" to app.bridge, "app" to app.id)
        .joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    return buildJsonObject {
        put("src", src)
        put("root", location.root.id)
        put("dir", location.path)
        put("path", name?.let { location.full(it) })
        put("bridge", app.bridge)
        put("startNew", app.startNew == true)
        put("copyWord", copyWord)
        put("local", local != null)
        put("name", local?.name ?: name)
        put("writable", local?.writable ?: true)
        put("types", buildJsonArray { app.types.forEach { add(JsonPrimitive(it)) } })
    }.toString()
}

private fun setCookie(access: FilesAccess, host: OfficeHost) {
    val c = access.sessions.current() ?: return
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setCookie(host.origin, "${c.name}=${c.token}; Path=/; Secure; HttpOnly; SameSite=Strict")
    cm.flush()
}

/** Die Schnittstelle der Rahmenseite; nichts davon gibt die Sitzung heraus. */
private class OfficeBridge(
    private val config: String,
    private val readLocal: () -> String,
    private val writeLocal: (String) -> String,
    private val renew: () -> Unit,
    private val onAsk: (OfficeAsk) -> Unit,
    private val onDirty: (Boolean) -> Unit,
    private val onError: (String, String) -> Unit,
    private val onSaved: (String) -> Unit,
    private val onPick: (String, Boolean) -> Unit,
    private val onKeep: (String, String) -> Unit,
) {
    @JavascriptInterface fun config(): String = config
    @JavascriptInterface fun readLocal(): String = runCatching { readLocal.invoke() }.getOrDefault("")
    @JavascriptInterface fun writeLocal(data: String): String = runCatching { writeLocal.invoke(data) }.getOrDefault("error")
    @JavascriptInterface fun ready() = Unit
    @JavascriptInterface fun opened(@Suppress("UNUSED_PARAMETER") size: Long) = Unit
    @JavascriptInterface fun renew(): Boolean = runCatching { renew.invoke() }.isSuccess
    @JavascriptInterface fun ask(kind: String, json: String, id: Int) {
        val name = runCatching { (Json.parseToJsonElement(json) as JsonObject)["name"]?.let { (it as JsonPrimitive).contentOrNull } }.getOrNull().orEmpty()
        onAsk(OfficeAsk(kind, name, id))
    }
    @JavascriptInterface fun dirty(on: Boolean) = onDirty(on)
    @JavascriptInterface fun error(kind: String, code: String) = onError(kind, code)
    @JavascriptInterface fun saved(path: String) = onSaved(path)
    @JavascriptInterface fun adopted(@Suppress("UNUSED_PARAMETER") path: String) = Unit
    @JavascriptInterface fun pick(accept: String, multiple: Boolean) = onPick(accept, multiple)
    @JavascriptInterface fun keepOnPhone(name: String, data: String) = onKeep(name, data)
}

/** Nur das NAS: Rahmenseite aus der App, /webapps/ und die nötigen Routen; alles andere bleibt zu. */
private class OfficeClient(private val context: Context, private val host: OfficeHost, private val onBadCert: () -> Unit) : WebViewClient() {
    private fun own(uri: Uri): Boolean = uri.scheme == "https" && uri.host == host.base.host && (uri.port.takeIf { it > 0 } ?: 443) == host.base.port

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !own(request.url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!own(uri)) return blocked()
        val path = uri.path.orEmpty()
        if (path == HOST_PATH) {
            return WebResourceResponse("text/html", "utf-8", context.assets.open("titanos-office.html")).apply {
                responseHeaders = mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "frame-ancestors 'none'; object-src 'none'")
            }
        }
        if (path.startsWith("/webapps/") || ALLOWED_API.any { path.startsWith(it) } || path == "/favicon.ico") return null
        return blocked()
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Selbst signiertes Zertifikat des NAS: gilt, wenn der Pin aus der Kopplung passt.
        val cert = error.certificate?.x509Certificate
        if (own(Uri.parse(error.url)) && host.accepts(cert)) handler.proceed() else { handler.cancel(); onBadCert() }
    }

    override fun onPageFinished(view: WebView, url: String) {
        // Ein öffentlich gültiges Zertifikat zählt nur, wo die Kopplung es erlaubt (wie PinnedTrustManager).
        val cert = view.certificate?.x509Certificate
        if (!host.allowPublic && cert != null && !host.accepts(cert)) {
            view.stopLoading()
            view.loadUrl("about:blank")
            onBadCert()
        }
    }
}

private fun acceptTypes(accept: String): Array<String> {
    val mimes = accept.split(',').map { it.trim() }.filter { it.contains('/') }
    return if (mimes.isEmpty()) arrayOf("*/*") else mimes.toTypedArray()
}

private const val PICK_LIMIT = 50L * 1024 * 1024
private const val LOCAL_LIMIT = 200L * 1024 * 1024

private fun readPicked(context: Context, uri: Uri): Pair<String, ByteArray>? {
    var name = "datei"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
    val bytes = context.contentResolver.openInputStream(uri)?.let { FilesApi.readLimited(it, PICK_LIMIT) } ?: return null
    return name to bytes
}

/** Eine Ausgabe (etwa ein PDF-Export) in Downloads auf dem Handy. */
private fun keepOnPhone(context: Context, name: String, bytes: ByteArray) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, TransferWorker.mimeOf(name))
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads")
    context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
}
