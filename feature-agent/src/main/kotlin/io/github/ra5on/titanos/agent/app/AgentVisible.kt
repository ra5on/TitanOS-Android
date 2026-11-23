package io.github.ra5on.titanos.agent.app

/**
 * Der Chat, der gerade vorne zu sehen ist (offen und die App im Vordergrund).
 * Die TitanOS-App zeigt für ihn keine Mitteilung „Antwort des KI-Agenten“ und
 * nimmt vorhandene aus der Leiste, wie das Web (`clearAgentNotifications`);
 * das NAS selbst schweigt über die Meldung „zu sehen“ (`/agent/chats/…/seen`).
 */
object AgentVisible {
    /** Wie viele Oberflächen einen Chat gerade vorne zeigen (Fenster und Leiste am Arbeitsplatz zeigen ihn zweimal). */
    private val counts = LinkedHashMap<String, Int>()

    /** Der zuletzt erschienene Chat, der noch zu sehen ist. */
    @Volatile
    var chat: String? = null
        private set

    /** Die App setzt hier, was beim Erscheinen eines Chats geschieht (Mitteilungen dazu weg). */
    @Volatile
    var onShown: (String) -> Unit = {}

    fun show(id: String) {
        synchronized(counts) {
            counts[id] = (counts[id] ?: 0) + 1
            chat = id
        }
        runCatching { onShown(id) }
    }

    /** Eine Oberfläche zeigt [id] nicht mehr; zu sehen bleibt er, solange eine andere ihn zeigt. */
    fun hide(id: String) {
        synchronized(counts) {
            val left = (counts[id] ?: 0) - 1
            if (left > 0) counts[id] = left else counts.remove(id)
            if (chat == id && left <= 0) chat = counts.keys.lastOrNull()
        }
    }

    /** Ob [link] (`/agent?chat=<id>`, auch `/agent?profile=assist&chat=<id>`) einen Chat meint, der gerade zu sehen ist. */
    fun shows(link: String?): Boolean {
        val id = chatOf(link) ?: return false
        return synchronized(counts) { counts.containsKey(id) }
    }

    /** Nur für Tests: nichts zu sehen. */
    internal fun reset() {
        synchronized(counts) {
            counts.clear()
            chat = null
        }
    }

    /** Die Kennung des Chats aus einem Link `/agent?chat=<id>` (auch mit `&profile=…`). */
    fun chatOf(link: String?): String? {
        if (link == null || !(link == "/agent" || link.startsWith("/agent?") || link.startsWith("/agent/"))) return null
        return link.substringAfter('?', "").split('&').firstOrNull { it.startsWith("chat=") }?.removePrefix("chat=")?.takeIf { it.isNotEmpty() }
    }
}
