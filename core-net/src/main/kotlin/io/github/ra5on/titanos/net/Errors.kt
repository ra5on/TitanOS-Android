package io.github.ra5on.titanos.net

import java.io.IOException

/** Eine Fehlerantwort des NAS: `{"error":{"code","message"}}`. */
open class ApiException(val status: Int, val code: String, message: String, val retryAfterSeconds: Int? = null) :
    IOException("HTTP $status $code: $message")

/** `401 app_unknown`: Das Gerät ist beendet, abgelaufen oder unbekannt. Die App zeigt die Kopplung wieder. */
class DeviceUnknownException(message: String = "app_unknown") : ApiException(401, "app_unknown", message)

/** 503: Das NAS ist gerade nicht bereit (z. B. `apps_unavailable`). Später erneut versuchen, Kopplung behalten. */
class NasNotReadyException(code: String, message: String, retryAfter: Int?) : ApiException(503, code, message, retryAfter)

/** 423 `locked`: Die Sitzung ist am NAS gesperrt. Nicht durch eine neue Anmeldung umgehen. */
class SessionLockedException(message: String) : ApiException(423, "locked", message)
