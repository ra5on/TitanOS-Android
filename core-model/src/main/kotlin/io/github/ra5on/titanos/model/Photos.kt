package io.github.ra5on.titanos.model

import kotlinx.serialization.Serializable

// Foto-Sicherung (TitanOS docs/ANDROID-APP.md „Foto-Sicherung“,
// backend/internal/api/appphotos.go).

/** Ein Foto für `app/photos/check` bzw. `verify`: SHA-256 (64 Hex-Ziffern, klein) und Größe. */
@Serializable
data class PhotoItem(
    val sha256: String,
    val size: Long,
    /** Wo die App es ablegen würde, relativ zum Ordner der Sicherung (nur check). */
    val path: String? = null,
)

@Serializable
data class PhotoCheckRequest(val root: String, val dir: String, val items: List<PhotoItem>)

@Serializable
data class PhotoVerifyRequest(val items: List<PhotoItem>)

/** state: `saved`, `missing` oder `unsure` (konnte jetzt nicht nachgerechnet werden). */
@Serializable
data class PhotoAnswer(val sha256: String, val state: String, val root: String = "", val path: String = "")

@Serializable
data class PhotoAnswers(val items: List<PhotoAnswer> = emptyList())

@Serializable
data class PhotoFinishRequest(val sha256: String)

/** Antwort von `app/photos/uploads/{id}/finish`; path ist der endgültige Ort in der Freigabe. */
@Serializable
data class PhotoFinished(
    val id: String = "",
    val size: Long = 0,
    val name: String = "",
    val path: String = "",
    val sha256: String = "",
    val root: String = "",
    val recorded: Boolean = false,
)

@Serializable
data class MkdirRequest(val root: String, val path: String)
