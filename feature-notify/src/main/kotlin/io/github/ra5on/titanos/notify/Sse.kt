package io.github.ra5on.titanos.notify

/** Ein Ereignis eines Server-Sent-Events-Stroms. */
data class SseEvent(val event: String, val data: String, val id: String?)

/**
 * Zerlegt einen SSE-Strom Zeile für Zeile (WHATWG „Server-sent events“):
 * `event:`, `data:` (mehrere Zeilen werden mit `\n` verbunden), `id:`,
 * Kommentare mit `:` werden übergangen, eine leere Zeile schickt das
 * Ereignis ab. Ein `\r` am Zeilenende wird entfernt (CRLF).
 */
class SseParser {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false
    private var id: String? = null

    /** Nimmt eine Zeile ohne Zeilenende; gibt bei einer leeren Zeile das fertige Ereignis zurück. */
    fun line(raw: String): SseEvent? {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if (!value.contains('\u0000')) id = value
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val out = if (hasData) SseEvent(event.ifEmpty { "message" }, data.toString(), id) else null
        event = ""
        data.setLength(0)
        hasData = false
        // id bleibt laut Standard bis zur nächsten id-Zeile die letzte Kennung.
        return out
    }
}
