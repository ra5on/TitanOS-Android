package io.github.ra5on.titanos.notify

import kotlinx.serialization.Serializable

/**
 * Eine Mitteilung vom NAS (TitanOS docs/ANDROID-APP.md „Mitteilungen“,
 * `GET /api/v1/app/notifications`). [id] ist die Marke `<epoch>-<nummer>`.
 */
@Serializable
data class Note(
    val id: String,
    /** RFC 3339, wie das NAS sie schickt. */
    val time: String = "",
    val category: String = Category.Nas.key,
    val severity: String = "info",
    val title: String = "",
    val text: String = "",
    val tag: String? = null,
    val link: String? = null,
    val app: String = "titanos",
) {
    val kind: Category get() = Category.of(category)

    /** Millisekunden seit 1970 (0, wenn die Zeit nicht lesbar ist). */
    val millis: Long get() = runCatching { java.time.Instant.parse(time).toEpochMilli() }.getOrDefault(0L)

    /** Schlüssel der Android-Mitteilung: gleiches Tag ersetzt die ältere, wie im Browser. */
    val notificationTag: String get() = tag?.takeIf { it.isNotBlank() } ?: id
}

/** Antwort von Abruf und `ready`. */
@Serializable
data class NoteList(
    val epoch: String = "",
    val latest: String = "",
    val reset: Boolean = false,
    val items: List<Note> = emptyList(),
)

/** Arten, wie das NAS sie schickt; je Art ein Android-Kanal und ein Schalter (Entwurf 72). */
enum class Category(val key: String) {
    Nas("nas"), Mail("mail"), Calendar("calendar"), Agent("agent"), Nvr("nvr");

    companion object {
        fun of(key: String): Category = entries.firstOrNull { it.key == key } ?: Nas
    }
}

/** Eine Marke `<epoch>-<nummer>`; Vergleich nur innerhalb einer Epoche. */
data class Marker(val epoch: String, val seq: Long) {
    override fun toString(): String = "$epoch-$seq"

    companion object {
        fun parse(s: String?): Marker? {
            if (s.isNullOrEmpty()) return null
            val i = s.lastIndexOf('-')
            if (i <= 0) return null
            val n = s.substring(i + 1).toLongOrNull() ?: return null
            return Marker(s.substring(0, i), n)
        }

        /** Die neuere von zwei Marken; eine aus einer anderen Epoche ersetzt die alte. */
        fun newer(old: String, candidate: String): String {
            val a = parse(old) ?: return candidate
            val b = parse(candidate) ?: return old
            if (a.epoch != b.epoch) return candidate
            return if (b.seq > a.seq) candidate else old
        }
    }
}
