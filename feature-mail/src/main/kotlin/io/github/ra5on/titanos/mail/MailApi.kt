package io.github.ra5on.titanos.mail

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.OutputStream

/** Go schickt leere Listen als null; hier gilt dann die Vorgabe. */
internal val MailJson: Json = Json(TitanJson) { coerceInputValues = true }

internal inline fun <reified T> Response.mailDecode(): T = use { r ->
    r.throwIfError()
    MailJson.decodeFromString(serializer<T>(), r.body.string())
}

internal inline fun <reified T> mailBody(value: T): RequestBody =
    MailJson.encodeToString(serializer<T>(), value).toRequestBody("application/json".toMediaType())

/** Eine Datei vom Handy für einen Anhang. */
class Upload(val name: String, val type: String, val bytes: ByteArray)

/** Filter der Listen und der Suche (1.12); `since`/`until` sind Tage JJJJ-MM-TT. */
data class Filters(
    val q: String = "",
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val from: String = "",
    val attachment: Boolean = false,
    val since: String = "",
    val until: String = "",
) {
    val any: Boolean get() = q.isNotBlank() || unread || flagged || from.isNotBlank() || attachment || since.isNotBlank() || until.isNotBlank()
    val anyBesideText: Boolean get() = unread || flagged || from.isNotBlank() || attachment || since.isNotBlank() || until.isNotBlank()

    fun params(): List<Pair<String, String>> = buildList {
        if (q.isNotBlank()) add("q" to q.trim())
        if (unread) add("unread" to "1")
        if (flagged) add("flagged" to "1")
        if (from.isNotBlank()) add("from" to from.trim())
        if (attachment) add("attachment" to "1")
        if (since.isNotBlank()) add("since" to since)
        if (until.isNotBlank()) add("until" to until)
    }
}

/**
 * Die Routen von Mail, genau die der Weboberfläche (web/src/lib/tmail/api.ts,
 * backend/internal/api/tmail.go). [stream] hat eine lange Lesezeit für
 * `GET /mail/events` (das NAS schickt alle 20 s ein Lebenszeichen).
 */
class MailApi(private val nas: NasConnection, private val stream: NasConnection = nas) {

    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/").addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    /** Pfad mit einer Kennung als eigenem Segment (kodiert). */
    private fun HttpUrl.Builder.acc(id: String, rest: String): HttpUrl.Builder =
        addPathSegment("mail").addPathSegment("accounts").addPathSegment(id).apply { if (rest.isNotEmpty()) addPathSegments(rest) }

    private fun accUrl(base: HttpUrl, id: String, rest: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/").acc(id, rest).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend inline fun <reified T> get(crossinline at: (HttpUrl) -> HttpUrl): T =
        withContext(Dispatchers.IO) { busyOnce { nas.execute { Request.Builder().url(at(it)).build() }.mailDecode<T>() } }

    private suspend inline fun <reified T> send(method: String, body: RequestBody?, crossinline at: (HttpUrl) -> HttpUrl): T =
        withContext(Dispatchers.IO) { busyOnce { nas.execute { Request.Builder().url(at(it)).method(method, body).build() }.mailDecode<T>() } }

    private suspend fun call(method: String, body: RequestBody?, at: (HttpUrl) -> HttpUrl) {
        withContext(Dispatchers.IO) {
            busyOnce {
                nas.execute {
                    val b = body ?: if (method == "DELETE" || method == "GET") null else ByteArray(0).toRequestBody(null)
                    Request.Builder().url(at(it)).method(method, b).build()
                }.use { it.throwIfError() }
            }
        }
    }

    /**
     * mail_busy (503, Retry-After 5): titand hat schon zwei große Aufträge
     * des Kontos oder keinen Speicher frei. Einmal nach der Pause noch einmal.
     */
    private suspend fun <T> busyOnce(run: () -> T): T = try {
        run()
    } catch (e: ApiException) {
        if (e.code != "mail_busy") throw e
        delay(((e.retryAfterSeconds ?: 5).coerceIn(1, 30)) * 1000L)
        run()
    }

    // Freigabe, Anbieter, Postfächer.
    /** Ob das Konto Mail nutzen darf (nie 403). */
    suspend fun access(): Boolean = get<Access> { url(it, "mail/access") }.enabled

    suspend fun provider(email: String): Provider = get { url(it, "mail/providers", listOf("email" to email)) }
    suspend fun accounts(): List<MailAccount> = get { url(it, "mail/accounts") }
    suspend fun createAccount(input: AccountInput): MailAccount = send("POST", mailBody(input)) { url(it, "mail/accounts") }
    suspend fun updateAccount(id: String, input: AccountInput): MailAccount = send("PUT", mailBody(input)) { accUrl(it, id, "") }
    suspend fun deleteAccount(id: String) = call("DELETE", null) { accUrl(it, id, "") }
    suspend fun test(req: TestRequest): TestResult = send("POST", mailBody(req)) { url(it, "mail/accounts/test") }

    // Ordner.
    suspend fun folders(id: String): List<MailFolder> = get { accUrl(it, id, "folders") }
    suspend fun createFolder(id: String, path: String) = call("POST", mailBody(mapOf("path" to path))) { accUrl(it, id, "folders") }
    suspend fun renameFolder(id: String, path: String, to: String) =
        call("PUT", mailBody(mapOf("path" to path, "to" to to))) { accUrl(it, id, "folders") }
    suspend fun deleteFolder(id: String, path: String) = call("DELETE", null) { accUrl(it, id, "folders", listOf("path" to path)) }

    // Listen, Suche, Nachricht.
    suspend fun messages(id: String, folder: String, filters: Filters, before: Long = 0, limit: Int = PAGE_SIZE): MessagePage =
        get {
            accUrl(it, id, "messages", buildList {
                add("folder" to folder)
                if (before > 0) add("before" to before.toString())
                add("limit" to limit.toString())
                addAll(filters.params())
            })
        }

    /** 1.15: „Alle Posteingänge“ (`inbox`) und „Markiert“ (`flagged`) über alle Postfächer. */
    suspend fun unified(kind: String, filters: Filters, cursor: String = "", limit: Int = PAGE_SIZE): UnifiedPage =
        get {
            url(it, "mail/unified", buildList {
                add("kind" to kind)
                addAll(filters.params())
                if (cursor.isNotEmpty()) add("cursor" to cursor)
                add("limit" to limit.toString())
            })
        }

    /** 1.12: die neuesten Treffer aller Ordner eines Postfachs. */
    suspend fun search(id: String, filters: Filters, limit: Int = 0): SearchResult =
        get { accUrl(it, id, "search", filters.params() + if (limit > 0) listOf("limit" to limit.toString()) else emptyList()) }

    /** Öffnen markiert als gelesen (das NAS tut es mit der Anfrage). */
    suspend fun message(id: String, folder: String, uid: Long, images: Boolean = false): Message =
        get { accUrl(it, id, "message", listOf("folder" to folder, "uid" to uid.toString(), "images" to if (images) "1" else "0")) }

    /** Schreibt einen Anhang nach [out]; [progress] bekommt geschriebene Bytes. */
    suspend fun part(id: String, folder: String, uid: Long, part: String, out: OutputStream, progress: (Long) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            nas.execute { Request.Builder().url(accUrl(it, id, "part", listOf("folder" to folder, "uid" to uid.toString(), "part" to part))).build() }
                .use { r ->
                    r.throwIfError()
                    var done = 0L
                    r.body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done)
                        }
                    }
                }
        }

    /** Speichert einen Anhang als Datei [path] in [root] (GET /files/roots); sie darf noch nicht da sein. */
    suspend fun savePart(id: String, folder: String, uid: Long, part: String, root: String, path: String) =
        call("POST", mailBody(SavePart(folder, uid, part, root, path))) { accUrl(it, id, "part/save") }

    suspend fun flags(id: String, folder: String, uids: List<Long>, add: List<String>, remove: List<String>) =
        call("POST", mailBody(FlagsRequest(folder, uids, add, remove))) { accUrl(it, id, "flags") }

    suspend fun move(id: String, folder: String, uids: List<Long>, to: String) =
        call("POST", mailBody(MoveRequest(folder, uids, to))) { accUrl(it, id, "move") }

    /** In den Papierkorb, im Papierkorb endgültig. */
    suspend fun delete(id: String, folder: String, uids: List<Long>) =
        call("POST", mailBody(UidsRequest(folder, uids))) { accUrl(it, id, "delete") }

    /** 1.12: ins Archiv des Postfachs (angelegt, wenn es keins gibt); Antwort: der Ordner. */
    suspend fun archive(id: String, folder: String, uids: List<Long>): String =
        send<ArchiveAnswer>("POST", mailBody(UidsRequest(folder, uids))) { accUrl(it, id, "archive") }.to

    // Schreiben.
    suspend fun send(id: String, meta: SendMeta, files: List<Upload>, nasFiles: List<NasFile>) =
        call("POST", sendForm(meta, files, nasFiles)) { accUrl(it, id, "send") }

    suspend fun draft(id: String, meta: SendMeta, files: List<Upload>, nasFiles: List<NasFile>): DraftSaved =
        send("POST", sendForm(meta, files, nasFiles)) { accUrl(it, id, "draft") }

    suspend fun contacts(q: String): List<MailContact> = get { url(it, "mail/contacts", listOf("q" to q)) }

    /** Vergisst eine gelernte Adresse (nie einen Kontakt). */
    suspend fun forgetContact(addr: String) = call("DELETE", null) { url(it, "mail/contacts", listOf("addr" to addr)) }

    suspend fun avatars(): Avatars = get { url(it, "mail/avatars") }

    /** Das Bild eines Kontakts (Absenderbild), JPEG; null ohne Bild. */
    suspend fun contactPhoto(book: String, id: String): ByteArray? = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(it.newBuilder().encodedPath("/api/v1/").addPathSegment("contacts").addPathSegment(book).addPathSegment(id).addPathSegment("photo").build()).build()
        }.use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }

    suspend fun settings(): MailSettings = get { url(it, "mail/settings") }
    suspend fun saveSettings(s: MailSettings): MailSettings = send("PUT", mailBody(s)) { url(it, "mail/settings") }

    // Reihenfolge der Postfächer und Ordner (Einstellungsart „order“).
    suspend fun order(): SideOrder = get<PrefsAnswer> { url(it, "preferences") }.order ?: SideOrder()

    /** Speichert nur die genannten Schlüssel (mail, mailAccounts, mailHidden); das NAS behält die anderen. */
    suspend fun saveOrder(part: JsonObject) = call("PUT", part.toString().toRequestBody("application/json".toMediaType())) { url(it, "preferences/order") }

    /** `GET /mail/events` bis zum Abbruch; endet, wenn der Strom endet oder scheitert. */
    fun events(): Flow<MailEvent> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            try {
                stream.execute { base ->
                    Request.Builder().url(url(base, "mail/events")).header("Accept", "text/event-stream").build()
                }.use { r ->
                    r.throwIfError()
                    trySend(MailEvent.Open)
                    val source = r.body.source()
                    val block = StringBuilder()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            parseEvent(block.toString())?.let { trySend(it) }
                            block.clear()
                        } else {
                            block.append(line).append('\n')
                        }
                    }
                }
            } catch (_: Exception) {
                // Verloren oder abgelehnt: das Modell verbindet neu.
            }
            channel.close()
        }
        awaitClose {
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    @kotlinx.serialization.Serializable
    private data class SavePart(val folder: String, val uid: Long, val part: String, val root: String, val path: String)

    @kotlinx.serialization.Serializable
    private data class FlagsRequest(val folder: String, val uids: List<Long>, val add: List<String>, val remove: List<String>)

    @kotlinx.serialization.Serializable
    private data class MoveRequest(val folder: String, val uids: List<Long>, val to: String)

    @kotlinx.serialization.Serializable
    private data class UidsRequest(val folder: String, val uids: List<Long>)

    @kotlinx.serialization.Serializable
    private data class ArchiveAnswer(val to: String = "")

    companion object {
        const val PAGE_SIZE = 50

        /** Der Körper von /send und /draft: `meta` als JSON, Dateien als `file`, NAS-Dateien als `nasFiles`. */
        fun sendForm(meta: SendMeta, files: List<Upload>, nasFiles: List<NasFile>): RequestBody =
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                addFormDataPart("meta", MailJson.encodeToString(SendMeta.serializer(), meta))
                for (f in files) {
                    addFormDataPart("file", f.name, f.bytes.toRequestBody((f.type.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()))
                }
                if (nasFiles.isNotEmpty()) {
                    addFormDataPart("nasFiles", MailJson.encodeToString(serializer<List<NasFile>>(), nasFiles))
                }
            }.build()

        /** Ein Block des Ereignisstroms („event: …“, „data: …“), wie live.svelte.ts. */
        fun parseEvent(block: String): MailEvent? {
            var type = "message"
            val data = StringBuilder()
            for (line in block.split('\n')) {
                if (line.isEmpty() || line.startsWith(":")) continue
                val at = line.indexOf(':')
                val field = if (at < 0) line else line.substring(0, at)
                val value = if (at < 0) "" else line.substring(at + 1).removePrefix(" ")
                when (field) {
                    "event" -> type = value
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                    }
                }
            }
            if (data.isEmpty()) return null
            val raw = runCatching { MailJson.parseToJsonElement(data.toString()).jsonObject }.getOrNull() ?: return null
            fun str(o: JsonObject, k: String) = runCatching { o[k]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val account = str(raw, "account")
            if (account.isEmpty()) return null
            val folder = str(raw, "folder")
            return when (type) {
                "mail" -> MailEvent.NewMail(account, folder, runCatching { raw["uid"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0)
                "status" -> {
                    val s = (raw["status"] as? JsonObject) ?: raw
                    val state = str(s, "state")
                    if (state !in setOf("ok", "connecting", "auth_failed", "unreachable", "off")) return null
                    MailEvent.Status(account, MailStatus(state, str(s, "code"), str(s, "since")))
                }
                "flags" -> {
                    val list: List<JsonElement> = runCatching { raw["uids"]?.jsonArray?.toList() }.getOrNull()
                        ?: listOfNotNull(raw["uid"])
                    MailEvent.FlagsChanged(account, folder, list.mapNotNull { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() })
                }
                else -> null
            }
        }

        /** Ein Teil der Reihenfolge zum Speichern. */
        fun orderPart(order: SideOrder, key: String): JsonObject = buildJsonObject {
            when (key) {
                "mailAccounts" -> put(key, MailJson.encodeToJsonElement(serializer<List<String>>(), order.mailAccounts))
                "mailHidden" -> put(key, MailJson.encodeToJsonElement(serializer<Map<String, List<String>>>(), order.mailHidden))
                else -> put(key, MailJson.encodeToJsonElement(serializer<Map<String, List<String>>>(), order.mail))
            }
        }
    }
}
