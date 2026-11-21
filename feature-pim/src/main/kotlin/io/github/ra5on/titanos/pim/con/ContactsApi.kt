package io.github.ra5on.titanos.pim.con

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

@Serializable
data class Soon(val date: String = "", val age: Int? = null)

/** Eine Zeile der Liste (GET /contacts). */
@Serializable
data class ContactItem(
    val id: String,
    val book: String = BOOK,
    val name: String = "",
    val sortKey: String = "",
    val org: String = "",
    val initials: String = "",
    val email: String = "",
    val hasPhoto: Boolean = false,
    val etag: String = "",
    val birthdaySoon: Soon? = null,
)

@Serializable
data class Value(val type: String = "other", val value: String = "", val label: String? = null)

@Serializable
data class PostalAddress(
    val type: String = "home",
    val label: String? = null,
    val street: String = "",
    val city: String = "",
    val postcode: String = "",
    val region: String = "",
    val country: String = "",
)

/** Ein Kontakt (GET /contacts/{book}/{id}). */
@Serializable
data class Contact(
    val id: String,
    val book: String = BOOK,
    val etag: String = "",
    val first: String = "",
    val last: String = "",
    val name: String = "",
    val org: String = "",
    val phones: List<Value> = emptyList(),
    val emails: List<Value> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val birthday: String = "",
    val notes: String = "",
    val hasPhoto: Boolean = false,
    val url: String = "",
    val modified: String = "",
)

@Serializable
data class TypedValue(val type: String, val value: String)

@Serializable
data class AddressInput(val type: String, val street: String, val city: String, val postcode: String, val region: String, val country: String)

/** Was das Formular schickt (Input im Web). */
@Serializable
data class ContactInput(
    val first: String = "",
    val last: String = "",
    val org: String = "",
    val phones: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<AddressInput> = emptyList(),
    val birthday: String = "",
    val notes: String = "",
)

@Serializable
data class Saved(val id: String = "", val book: String = BOOK, val etag: String = "", val item: ContactItem? = null, val hasPhoto: Boolean? = null)

@Serializable
data class Book(val id: String, val name: String = "", val count: Int = 0)

@Serializable
data class Group(val id: String, val book: String = BOOK, val etag: String = "", val name: String = "", val members: List<String> = emptyList(), val other: Int = 0)

@Serializable
data class Overview(val books: List<Book> = emptyList(), val groups: List<Group> = emptyList(), val duplicates: Int = 0)

@Serializable
data class DuplicateMatch(val kind: String = "", val value: String = "", val ids: List<String> = emptyList())

@Serializable
data class DuplicateSet(val book: String = BOOK, val ids: List<String> = emptyList(), val matches: List<DuplicateMatch> = emptyList())

@Serializable
private data class Duplicates(val sets: List<DuplicateSet> = emptyList())

@Serializable
data class Suggestion(val name: String = "", val addr: String = "", val count: Int = 0)

@Serializable
private data class SearchAnswer(val contacts: List<ContactItem> = emptyList(), val suggestions: List<Suggestion> = emptyList())

@Serializable
data class ContactsImport(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0, val failed: List<JsonObject> = emptyList())

@Serializable
private data class Create(val book: String, val contact: ContactInput)

@Serializable
private data class Update(val etag: String, val contact: ContactInput)

@Serializable
private data class GroupCreate(val book: String, val name: String, val members: List<String>)

@Serializable
private data class GroupUpdate(val etag: String, val name: String? = null, val add: List<String>? = null, val remove: List<String>? = null)

@Serializable
data class EtagRef(val id: String, val etag: String)

@Serializable
private data class Merge(val book: String, val keep: EtagRef, val drop: EtagRef, val contact: ContactInput, val photo: String)

@Serializable
private data class FromSuggestion(val addr: String, val book: String)

@Serializable
private data class Me(val me: String? = null)

@Serializable
data class ContactsPrefs(val me: String? = null)

const val BOOK = "kontakte"

/**
 * Die Routen von Kontakte, genau die der Weboberfläche
 * (web/src/lib/pim/contacts-api.ts; backend/internal/api/contacts*.go).
 */
class ContactsApi(private val nas: NasConnection) {
    private fun url(base: HttpUrl, segments: List<String>, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1").apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend inline fun <reified T> get(segments: List<String>, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, segments, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> send(method: String, segments: List<String>, body: B, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, segments, query)).method(method, jsonBody(body)).build() }.decode<T>() }

    private suspend fun delete(segments: List<String>, query: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, segments, query)).delete().build() }.use { it.throwIfError() }
    }

    private var listTag = ""
    private var cached: List<ContactItem>? = null

    /** Die ganze Liste; unverändert am NAS (304) die bekannte. */
    suspend fun list(): List<ContactItem> = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(url(it, listOf("contacts"))).apply { if (listTag.isNotEmpty() && cached != null) header("If-None-Match", listTag) }.build()
        }.use { r ->
            if (r.code == 304 && cached != null) return@use cached!!
            r.throwIfError()
            val list = TitanJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ContactItem.serializer()), r.body.string())
            listTag = r.header("ETag").orEmpty()
            cached = list
            list
        }
    }

    fun forgetList() { listTag = ""; cached = null }

    suspend fun contact(id: String): Contact = get(listOf("contacts", BOOK, id))
    suspend fun create(input: ContactInput): Saved = send<Create, Saved>("POST", listOf("contacts"), Create(BOOK, input)).also { forgetList() }
    suspend fun update(id: String, etag: String, input: ContactInput): Saved = send<Update, Saved>("PUT", listOf("contacts", BOOK, id), Update(etag, input)).also { forgetList() }
    suspend fun remove(id: String, etag: String) = delete(listOf("contacts", BOOK, id), listOf("etag" to etag)).also { forgetList() }

    suspend fun photo(id: String, version: String = ""): ByteArray? = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("contacts", BOOK, id, "photo"), if (version.isNotEmpty()) listOf("v" to version) else emptyList())).build() }.use { r ->
            if (r.code == 404) null else { r.throwIfError(); r.body.bytes() }
        }
    }

    suspend fun putPhoto(id: String, etag: String, name: String, bytes: ByteArray, type: String): Saved = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, bytes.toRequestBody(type.toMediaType())).build()
        nas.execute { Request.Builder().url(url(it, listOf("contacts", BOOK, id, "photo"), listOf("etag" to etag))).put(body).build() }.decode<Saved>()
    }.also { forgetList() }

    suspend fun removePhoto(id: String, etag: String): Saved = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("contacts", BOOK, id, "photo"), listOf("etag" to etag))).delete().build() }.decode<Saved>()
    }.also { forgetList() }

    suspend fun overview(): Overview = get(listOf("contacts", "overview"))
    suspend fun search(q: String): Pair<List<ContactItem>, List<Suggestion>> = get<SearchAnswer>(listOf("contacts", "search"), listOf("q" to q)).let { it.contacts to it.suggestions }
    suspend fun duplicates(): List<DuplicateSet> = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("contacts", "duplicates"))).build() }.use { r ->
            r.throwIfError()
            val text = r.body.string()
            // Liste oder {sets:[…]}: beides lesen.
            runCatching { TitanJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DuplicateSet.serializer()), text) }
                .getOrElse { TitanJson.decodeFromString(Duplicates.serializer(), text).sets }
        }
    }

    suspend fun merge(keep: EtagRef, drop: EtagRef, input: ContactInput, photo: String): Saved =
        send<Merge, Saved>("POST", listOf("contacts", "merge"), Merge(BOOK, keep, drop, input, photo)).also { forgetList() }

    suspend fun createGroup(name: String, members: List<String> = emptyList()): Group = send<GroupCreate, Group>("POST", listOf("contacts", "groups"), GroupCreate(BOOK, name, members)).also { forgetList() }
    suspend fun updateGroup(g: Group, name: String? = null, add: List<String>? = null, remove: List<String>? = null): Group =
        send<GroupUpdate, Group>("PUT", listOf("contacts", "groups", g.id), GroupUpdate(g.etag, name, add, remove), listOf("book" to BOOK)).also { forgetList() }
    suspend fun removeGroup(g: Group) = delete(listOf("contacts", "groups", g.id), listOf("book" to BOOK, "etag" to g.etag)).also { forgetList() }

    suspend fun suggestions(q: String = ""): List<Suggestion> = get(listOf("contacts", "suggestions"), if (q.isNotEmpty()) listOf("q" to q) else emptyList())
    suspend fun fromSuggestion(addr: String): Saved = send<FromSuggestion, Saved>("POST", listOf("contacts", "from-suggestion"), FromSuggestion(addr, BOOK)).also { forgetList() }

    suspend fun prefs(): ContactsPrefs = runCatching { get<ContactsPrefs>(listOf("preferences", "contacts")) }.getOrDefault(ContactsPrefs())
    suspend fun setMe(id: String?) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("preferences", "contacts"))).put(jsonBody(Me(id))).build() }.use { it.throwIfError() }
    }

    suspend fun importVcf(bytes: ByteArray, replace: Boolean): ContactsImport = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "import.vcf", bytes.toRequestBody("text/vcard".toMediaType())).build()
        nas.execute { Request.Builder().url(url(it, listOf("contacts", "books", BOOK, "import"), listOf("onDuplicate" to if (replace) "replace" else "skip"))).post(body).build() }.decode<ContactsImport>()
    }.also { forgetList() }

    suspend fun exportVcf(out: OutputStream) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("contacts", "books", BOOK, "export"))).build() }.use { r ->
            r.throwIfError(); r.body.byteStream().use { it.copyTo(out) }
        }
    }
}

fun isConflict(e: Throwable) = e is ApiException && (e.status == 409 || e.status == 412)
