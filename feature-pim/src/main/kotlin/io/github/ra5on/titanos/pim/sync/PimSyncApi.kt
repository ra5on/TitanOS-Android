package io.github.ra5on.titanos.pim.sync

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class SyncCollection(
    val id: String,
    val name: String = "",
    val color: String = "",
    val order: Int = 0,
    val description: String? = null,
    val ctag: String = "",
    val syncToken: String = "",
    val count: Int = 0,
    val readOnly: Boolean = false,
    val virtual: Boolean = false,
)

@Serializable
data class SyncCollections(val collections: List<SyncCollection> = emptyList())

@Serializable
data class SyncChange(val name: String, val etag: String? = null, val deleted: Boolean = false)

@Serializable
data class SyncChanges(val token: String = "", val truncated: Boolean = false, val changes: List<SyncChange> = emptyList())

@Serializable
data class SyncObject(val name: String, val etag: String, val data: String)

@Serializable
data class SyncObjects(val objects: List<SyncObject> = emptyList(), val missing: List<String> = emptyList())

@Serializable
data class SyncWritten(val etag: String = "", val created: Boolean = false, val rewritten: Boolean = false)

@Serializable
private data class Names(val names: List<String>)

/** Der Eintrag wurde am NAS inzwischen geändert oder gelöscht (412). */
class SyncConflictException : Exception("pim_conflict")

/** Dieselbe UID gibt es schon unter [existing] (409). */
class SyncUidConflictException(val existing: String?) : Exception("pim_uid_conflict")

/** Die Marke gilt nicht mehr (410): ohne Marke neu anfangen. */
class SyncTokenGoneException : Exception("pim_sync_token")

/** Das NAS kennt die Routen nicht: es braucht TitanOS 1.19. */
class SyncNotSupportedException : Exception("needs TitanOS 1.19")

/**
 * Die Abgleich-Routen der App (`/api/v1/app/pim/…`, TitanOS 1.19,
 * backend/internal/api/apppim.go): gespeicherte Einträge mit ETag.
 */
class PimSyncApi(private val nas: NasConnection) {
    private fun url(base: HttpUrl, vararg segments: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/app/pim").apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw when {
            e.status == 412 -> SyncConflictException()
            e.status == 410 -> SyncTokenGoneException()
            e.status == 404 && e.code == "not_found" -> SyncNotSupportedException()
            else -> e
        }
    }

    fun collections(kind: String): List<SyncCollection> = mapped {
        nas.execute { Request.Builder().url(url(it, kind)).build() }.decode<SyncCollections>().collections
    }

    fun changes(kind: String, col: String, token: String): SyncChanges = mapped {
        nas.execute { Request.Builder().url(url(it, kind, col, "changes", query = listOf("token" to token))).build() }.decode<SyncChanges>()
    }

    fun objects(kind: String, col: String, names: List<String>): SyncObjects = mapped {
        nas.execute { Request.Builder().url(url(it, kind, col, "objects")).post(jsonBody(Names(names))).build() }.decode<SyncObjects>()
    }

    /** [etag] null: nur neu anlegen (If-None-Match: *), sonst nur diesen Stand ersetzen. */
    fun put(kind: String, col: String, name: String, data: String, etag: String?): SyncWritten {
        val type = if (kind == "contacts") "text/vcard; charset=utf-8" else "text/calendar; charset=utf-8"
        return try {
            mapped {
                nas.execute {
                    Request.Builder().url(url(it, kind, col, "objects", name)).put(data.toRequestBody(type.toMediaType()))
                        .apply { if (etag == null) header("If-None-Match", "*") else header("If-Match", "\"$etag\"") }.build()
                }.use { r ->
                    if (r.code == 409) {
                        val existing = r.header("X-Conflict-Name")
                        r.close()
                        throw SyncUidConflictException(existing)
                    }
                    r.decode<SyncWritten>()
                }
            }
        } catch (e: SyncUidConflictException) {
            throw e
        }
    }

    /** Löscht; ein schon fehlender Eintrag gilt als gelöscht. */
    fun delete(kind: String, col: String, name: String, etag: String?) {
        mapped {
            nas.execute {
                Request.Builder().url(url(it, kind, col, "objects", name)).delete()
                    .apply { if (etag != null) header("If-Match", "\"$etag\"") }.build()
            }.use { r ->
                if (r.code == 404) return@use
                r.throwIfError()
            }
        }
    }
}
