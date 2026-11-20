package io.github.ra5on.titanos.model

import kotlinx.serialization.Serializable

/** Ein Eintrag aus `GET /api/v1/files/roots`. id etwa `share/Documents`. */
@Serializable
data class FileRoot(
    val id: String,
    val name: String,
    val kind: String = "",
    val writable: Boolean = false,
    val recycle: Boolean = false,
    val recycleDays: Int? = null,
    val bins: List<String>? = null,
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    /** Der Volltext-Index deckt die Ablage ab (Inhaltssuche möglich). */
    val indexed: Boolean = false,
)

/** Ein Eintrag aus `GET /api/v1/files/list`. modified: RFC 3339 (UTC). */
@Serializable
data class FileEntry(
    val name: String,
    val dir: Boolean = false,
    val size: Long = 0,
    val modified: String = "",
)

@Serializable
data class RenameRequest(val root: String, val from: String, val to: String)

@Serializable
data class DeleteRequest(val root: String, val paths: List<String>, val permanent: Boolean)

@Serializable
data class UndoResponse(val undo: String = "")

@Serializable
data class UploadStartRequest(val root: String, val dir: String, val name: String, val size: Long, val conflict: String = "")

@Serializable
data class UploadStatus(
    val id: String,
    val offset: Long = 0,
    val size: Long = 0,
    val name: String = "",
    val busy: Boolean? = null,
)
