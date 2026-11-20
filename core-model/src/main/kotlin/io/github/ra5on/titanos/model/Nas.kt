package io.github.ra5on.titanos.model

import kotlinx.serialization.Serializable

/** `GET /api/v1/session`: öffentlich, antwortet nie mit 401 (user ist dann null). */
@Serializable
data class PublicSession(
    val hostname: String = "",
    val version: String? = null,
    val user: SessionUser? = null,
    val setupRequired: Boolean = false,
    val locked: Boolean? = null,
)

/** `GET /api/v1/system` (Ausschnitt). */
@Serializable
data class SystemInfo(
    val hostname: String = "",
    val version: String = "",
    val uptimeSeconds: Long = 0,
)

/** `GET /api/v1/storage` (Ausschnitt). */
@Serializable
data class StorageInfo(val volumes: List<Volume> = emptyList())

/** state: ok, degraded, rebuilding, syncing, checking, reshaping, failed, missing. */
@Serializable
data class Volume(
    val name: String,
    val level: String = "",
    val filesystem: String = "",
    val state: String = "",
    val mounted: Boolean = false,
    val sizeBytes: Long = 0,
    val usedBytes: Long = 0,
)

/** Ein Messpunkt aus `GET /api/v1/monitoring/history` (nur Besitzer/Administratoren). */
@Serializable
data class MonitorSample(
    val at: String = "",
    val cpu: Double = 0.0,
    val ramTotal: Long = 0,
    val ramAvailable: Long = 0,
)
