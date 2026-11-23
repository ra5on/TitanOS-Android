package io.github.ra5on.titanos.agent.app

/**
 * Welche Antworten des KI-Agenten schon als Mitteilung in der Leiste stehen
 * (1.03). Zwei Stellen melden eine Antwort, während der Chat nicht zu sehen
 * ist: die App selbst ([io.github.ra5on.titanos.agent.system.AnswerNotifier],
 * solange ihr Modell dem Lauf folgt) und das NAS (Web-Push, über
 * `/app/notifications` in der TitanOS-App). Seit die App dem NAS beim
 * Verlassen sofort „nicht mehr zu sehen“ sagt, schicken oft beide dieselbe
 * Antwort. Wer zuerst kommt, klingelt; die zweite Meldung desselben Laufs
 * bleibt still. Beide stehen außerdem unter demselben Schlüssel in der Leiste
 * (Tag `agent-<chat>`), sodass auch eine verpasste Erkennung nur ersetzt.
 *
 * Dieselbe Antwort erkennt die App so:
 *  - die andere Stelle meldete denselben Chat vor höchstens [RUN_MILLIS]
 *    (beide melden das Ende desselben Laufs, Sekunden auseinander), und der
 *    Chat war seitdem nicht zu sehen, oder
 *  - die andere Stelle meldete denselben Text (das NAS kürzt auf 180 Zeichen
 *    mit „…“) vor höchstens [TEXT_MILLIS] (ohne Dauerverbindung kommt das NAS
 *    erst mit dem Abruf alle 15 Minuten).
 * Eine weitere Antwort derselben Stelle (Antworten aus der Mitteilung, gleich
 * die nächste Antwort) klingelt wieder.
 */
object AgentNotices {
    enum class Source { App, Nas }

    /** [cut]: der Text ist gekürzt (endet auf „…“); [open]: der Chat war seitdem nicht zu sehen. */
    private data class Shown(val source: Source, val text: String, val cut: Boolean, val at: Long, val open: Boolean = true)

    const val RUN_MILLIS = 30_000L
    const val TEXT_MILLIS = 30 * 60_000L

    /** Je Chat die letzten gemeldeten Antworten (höchstens vier). */
    private val shown = LinkedHashMap<String, ArrayDeque<Shown>>()

    /**
     * Soll [source] die Antwort [text] im Chat [chat] jetzt zeigen? Wenn ja,
     * merkt es sich die Meldung; nein, wenn sie schon in der Leiste steht.
     */
    fun claim(chat: String, text: String, source: Source, now: Long = System.currentTimeMillis()): Boolean = synchronized(shown) {
        val list = shown.getOrPut(chat) { ArrayDeque() }
        list.removeAll { now - it.at > TEXT_MILLIS }
        val key = normalize(text)
        val cut = text.trim().endsWith("…")
        // Nur die jeweils andere Stelle kann denselben Lauf noch einmal melden.
        val dup = list.any { old ->
            old.source != source && ((old.open && now - old.at <= RUN_MILLIS) || sameText(old.text, old.cut, key, cut))
        }
        if (dup) return false
        list.addLast(Shown(source, key, cut, now))
        while (list.size > 4) list.removeFirst()
        if (shown.size > 64) shown.keys.firstOrNull()?.let { shown.remove(it) }
        true
    }

    /**
     * Der Chat ist erschienen: was in der Leiste stand, ist gesehen. Die nächste
     * Antwort klingelt wieder, auch wenn sie bald kommt; nur eine späte Meldung
     * derselben Antwort (gleicher Text) bleibt still.
     */
    fun seen(chat: String) {
        synchronized(shown) {
            val list = shown[chat] ?: return
            val closed = list.map { it.copy(open = false) }
            list.clear()
            list.addAll(closed)
        }
    }

    /** Nur für Tests. */
    internal fun reset() {
        synchronized(shown) { shown.clear() }
    }

    private fun normalize(text: String): String = text.trim().removeSuffix("…").trim().replace(Regex("\\s+"), " ")

    /** Gleich, auch wenn eine Seite gekürzt ist (das NAS schneidet nach 179 Zeichen ab und hängt „…“ an). */
    private fun sameText(a: String, aCut: Boolean, b: String, bCut: Boolean): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return (aCut && b.startsWith(a)) || (bCut && a.startsWith(b))
    }
}
