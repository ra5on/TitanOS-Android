package io.github.ra5on.titanos.mail

import android.content.Context

/** Was beim Wischen nach rechts bzw. ganz nach links geschieht. */
enum class SwipeAction { Seen, Flag, Archive, Delete, Move, Junk, None }

/** Externe Bilder: wie im Web nur auf Tippen, oder immer bzw. nur von Kontakten. */
enum class ImagesMode { Ask, Contacts, Always }

/** Nach Löschen, Verschieben, Archivieren einer offenen Nachricht. */
enum class AfterRemove { Next, List }

/**
 * Was Mail auf diesem Handy behält (wie prefs.ts im Browser je Gerät):
 * Unterhaltungen, Favoriten, eingeklappte Postfächer und die
 * Android-eigenen Wahlmöglichkeiten. Nur in den App-Einstellungen, nichts am NAS.
 */
data class MailPrefs(
    val threads: Boolean = true,
    val avatars: Boolean = true,
    val preview: Boolean = true,
    val swipeRight: SwipeAction = SwipeAction.Seen,
    val swipeLeft: SwipeAction = SwipeAction.Delete,
    val confirmDelete: Boolean = false,
    val images: ImagesMode = ImagesMode.Ask,
    val afterRemove: AfterRemove = AfterRemove.List,
    val autosaveSeconds: Int = 30,
    val quoteReply: Boolean = true,
    val favourites: List<String> = emptyList(),
    val collapsed: Set<String> = emptySet(),
    val collapsedFolders: Set<String> = emptySet(),
) {
    companion object {
        private const val FILE = "mail"
        val AUTOSAVE = listOf(0, 15, 30, 60, 300)

        fun load(context: Context): MailPrefs {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val d = MailPrefs()
            fun <E : Enum<E>> e(key: String, values: Array<E>, fallback: E): E =
                values.firstOrNull { it.name == p.getString(key, null) } ?: fallback
            return MailPrefs(
                threads = p.getBoolean("threads", d.threads),
                avatars = p.getBoolean("avatars", d.avatars),
                preview = p.getBoolean("preview", d.preview),
                swipeRight = e("swipeRight", SwipeAction.entries.toTypedArray(), d.swipeRight),
                swipeLeft = e("swipeLeft", SwipeAction.entries.toTypedArray(), d.swipeLeft),
                confirmDelete = p.getBoolean("confirmDelete", d.confirmDelete),
                images = e("images", ImagesMode.entries.toTypedArray(), d.images),
                afterRemove = e("afterRemove", AfterRemove.entries.toTypedArray(), d.afterRemove),
                autosaveSeconds = p.getInt("autosave", d.autosaveSeconds).takeIf { it in AUTOSAVE } ?: d.autosaveSeconds,
                quoteReply = p.getBoolean("quoteReply", d.quoteReply),
                favourites = p.getString("favourites", null)?.split('\u0002')?.filter { it.isNotEmpty() } ?: emptyList(),
                collapsed = p.getStringSet("collapsed", emptySet())!!.toSet(),
                collapsedFolders = p.getStringSet("collapsedFolders", emptySet())!!.toSet(),
            )
        }

        fun save(context: Context, v: MailPrefs) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("threads", v.threads)
                .putBoolean("avatars", v.avatars)
                .putBoolean("preview", v.preview)
                .putString("swipeRight", v.swipeRight.name)
                .putString("swipeLeft", v.swipeLeft.name)
                .putBoolean("confirmDelete", v.confirmDelete)
                .putString("images", v.images.name)
                .putString("afterRemove", v.afterRemove.name)
                .putInt("autosave", v.autosaveSeconds)
                .putBoolean("quoteReply", v.quoteReply)
                .putString("favourites", v.favourites.joinToString("\u0002"))
                .putStringSet("collapsed", v.collapsed)
                .putStringSet("collapsedFolders", v.collapsedFolders)
                .apply()
        }
    }
}

/** Ein Ordner eines Postfachs als ein Text (Favoriten, eingeklappte Ordner). */
fun folderRef(account: String, path: String) = "$account\u0000$path"
