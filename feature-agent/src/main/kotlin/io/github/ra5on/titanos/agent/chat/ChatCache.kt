package io.github.ra5on.titanos.agent.chat

import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.Serializable
import java.io.File

/** Der letzte bekannte Stand der Chatliste, für den Start ohne NAS. */
@Serializable
data class CachedLists(
    val at: String = "",
    val chats: List<ChatInfo> = emptyList(),
    val projects: List<Project> = emptyList(),
    val status: AgentStatus? = null,
)

/**
 * Ein kleiner Zwischenspeicher in den privaten Dateien der App: nur Titel,
 * Vorschau und Zeiten der Chats, Projekte und welches Modell gilt – keine
 * ganzen Chats. Wie die Web-App seit 1.07 (docs/PWA.md): ohne NAS sieht man
 * den letzten Stand, Änderungen scheitern sofort.
 */
class ChatCache(private val file: File?) {
    fun load(): CachedLists? {
        val f = file ?: return null
        return try {
            if (!f.exists()) null else TitanJson.decodeFromString(CachedLists.serializer(), f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(lists: CachedLists) {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(TitanJson.encodeToString(CachedLists.serializer(), lists))
            tmp.renameTo(f)
        } catch (_: Exception) {
            // Nur ein Zwischenspeicher: ohne ihn fehlt beim nächsten Start ohne NAS die Liste.
        }
    }

    fun clear() {
        file?.delete()
    }
}
