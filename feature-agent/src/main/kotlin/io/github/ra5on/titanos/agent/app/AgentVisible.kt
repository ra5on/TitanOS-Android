package io.github.ra5on.titanos.agent.app

/**
 * Der Chat, der gerade vorne zu sehen ist (offen und die App im Vordergrund).
 * Die TitanOS-App zeigt für ihn keine Mitteilung „Antwort des KI-Agenten“ und
 * nimmt vorhandene aus der Leiste, wie das Web (`clearAgentNotifications`);
 * das NAS selbst schweigt über die Meldung „zu sehen“ (`/agent/chats/…/seen`).
 */
object AgentVisible {
    @Volatile
    var chat: String? = null
        private set

    /** Die App setzt hier, was beim Erscheinen eines Chats geschieht (Mitteilungen dazu weg). */
    @Volatile
    var onShown: (String) -> Unit = {}

    fun show(id: String) {
        chat = id
        runCatching { onShown(id) }
    }

    fun hide(id: String) {
        if (chat == id) chat = null
    }

    /** Ob [link] (`/agent?chat=<id>`) den Chat meint, der gerade zu sehen ist. */
    fun shows(link: String?): Boolean {
        val id = chat ?: return false
        return chatOf(link) == id
    }

    /** Die Kennung des Chats aus einem Link `/agent?chat=<id>` (auch mit `&profile=…`). */
    fun chatOf(link: String?): String? {
        if (link == null || !(link == "/agent" || link.startsWith("/agent?") || link.startsWith("/agent/"))) return null
        return link.substringAfter('?', "").split('&').firstOrNull { it.startsWith("chat=") }?.removePrefix("chat=")?.takeIf { it.isNotEmpty() }
    }
}
