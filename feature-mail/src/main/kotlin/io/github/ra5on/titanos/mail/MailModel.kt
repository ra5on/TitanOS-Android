package io.github.ra5on.titanos.mail

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.min

/** Zustand der Live-Verbindung; `Down` erst nach 8 s Lücke (live.svelte.ts). */
enum class Live { Off, Connected, Down }

/** „Alle Posteingänge“ und „Markiert“ über alle Postfächer heißen Postfach „*“. */
const val ALL = "*"

/**
 * Was die Seiten von Mail teilen (store.svelte.ts): Freigabe, Postfächer mit
 * Zustand, Ordner je Postfach, Reihenfolge, Absenderbilder, Live-Strom.
 * Gelesen vom NAS; auf dem Handy bleibt nur, was [MailPrefs] hält.
 */
class MailModel(
    val api: MailApi,
    private val context: Context?,
    val scope: CoroutineScope,
    val autoload: Boolean = true,
) {
    var access by mutableStateOf<Boolean?>(null)
    var accessError by mutableStateOf<Throwable?>(null)
    var accounts by mutableStateOf<List<MailAccount>?>(null)
    var accountsError by mutableStateOf<Throwable?>(null)
    val folders = mutableStateMapOf<String, List<MailFolder>>()
    val folderErrors = mutableStateMapOf<String, Throwable>()
    var order by mutableStateOf(SideOrder())
    var orderError by mutableStateOf<Throwable?>(null)
    var prefs by mutableStateOf(context?.let { MailPrefs.load(it) } ?: MailPrefs())
        private set
    var live by mutableStateOf(Live.Off)
    var avatarBook by mutableStateOf("kontakte")
    val avatarIds = mutableStateMapOf<String, String>()
    val photos = mutableStateMapOf<String, ImageBitmap>()
    private val photoAsked = HashSet<String>()

    /** Steigt, wenn eine offene Liste neu lesen soll (Live-Ereignis, Änderung anderswo). */
    var listVersion by mutableIntStateOf(0)
        private set
    var refreshedAt by mutableStateOf<Instant?>(null)

    /** Die Listen der Ordner, damit „Zurück“ dieselbe Liste mit Stand zeigt. */
    private val lists = LinkedHashMap<String, MailList>()

    fun updatePrefs(next: MailPrefs) {
        prefs = next
        context?.let { MailPrefs.save(it, next) }
    }

    /** Einmal beim Öffnen von Mail: Freigabe, dann Postfächer, Ordner, Reihenfolge, Bilder. */
    fun start() {
        if (!autoload) return
        scope.launch {
            try {
                access = api.access()
                accessError = null
            } catch (e: Exception) {
                accessError = e
                return@launch
            }
            if (access != true) return@launch
            launch { runCatching { api.order() }.onSuccess { order = it } }
            loadAccounts()
            launch { loadAvatars() }
        }
    }

    suspend fun loadAccounts() {
        try {
            val got = api.accounts()
            accounts = got
            accountsError = null
            for (a in got) if (folders[a.id] == null) refreshFolders(a.id, now = true)
            for (id in folders.keys.toList()) if (got.none { it.id == id }) folders.remove(id)
        } catch (e: Exception) {
            accountsError = e
            if (accounts == null) accounts = emptyList()
        }
    }

    // ---- Ordner ----

    private val folderJobs = HashMap<String, Job>()
    private val folderFirst = HashMap<String, Long>()

    /**
     * Liest die Ordner neu, sobald Änderungen 3 s ruhen, spätestens nach 10 s
     * (refreshFolders): zehn gelesene Mails kosten eine Anfrage.
     */
    fun refreshFolders(id: String, now: Boolean = false) {
        if (!autoload) return
        folderJobs[id]?.cancel()
        val first = folderFirst.getOrPut(id) { System.currentTimeMillis() }
        val wait = if (now) 0L else min(QUIET, first + MOST - System.currentTimeMillis()).coerceAtLeast(0)
        folderJobs[id] = scope.launch {
            delay(wait)
            folderFirst.remove(id)
            try {
                folders[id] = api.folders(id)
                folderErrors.remove(id)
            } catch (e: Exception) {
                folderErrors[id] = e
                if (folders[id] == null) folders[id] = emptyList()
            }
        }
    }

    /** Die Zahlen auf der Seite sofort ändern (nie unter 0); die Antwort des NAS ersetzt sie. */
    fun adjust(account: String, folder: String, unread: Int = 0, total: Int = 0) {
        val list = folders[account] ?: return
        folders[account] = list.map {
            if (it.path != folder) it else it.copy(unread = (it.unread + unread).coerceAtLeast(0), total = (it.total + total).coerceAtLeast(0))
        }
        if (unread != 0 && list.any { it.path == folder && it.role == "inbox" }) {
            accounts = accounts?.map { if (it.id == account) it.copy(unread = (it.unread + unread).coerceAtLeast(0)) else it }
        }
        refreshFolders(account)
    }

    fun folderOf(account: String, role: String): MailFolder? = folders[account]?.firstOrNull { it.role == role }
    fun findFolder(account: String, path: String): MailFolder? = folders[account]?.firstOrNull { it.path == path }
    fun inboxOf(account: String): String = folderOf(account, "inbox")?.path ?: "INBOX"
    fun account(id: String): MailAccount? = accounts?.firstOrNull { it.id == id }

    /** Die Postfächer in der Reihenfolge des Kontos; das erste ist das Hauptpostfach. */
    fun orderedAccounts(): List<MailAccount> = applyOrder(accounts.orEmpty(), order.mailAccounts) { it.id }

    /** Das Postfach, aus dem „Neue E-Mail“ schreibt: das offene, sonst das Hauptpostfach. */
    fun mainAccount(open: String = ""): MailAccount? =
        (if (open.isNotEmpty() && open != ALL) account(open) else null) ?: orderedAccounts().firstOrNull()

    fun hidden(account: String): List<String> = order.mailHidden[account].orEmpty()

    // ---- Reihenfolge (Einstellungsart „order“) ----

    private val orderJobs = HashMap<String, Job>()

    private fun saveOrderSoon(key: String) {
        orderJobs[key]?.cancel()
        orderJobs[key] = scope.launch {
            delay(600)
            try {
                api.saveOrder(MailApi.orderPart(order, key))
                orderError = null
            } catch (e: Exception) {
                orderError = e
            }
        }
    }

    fun setAccountOrder(ids: List<String>) {
        order = order.copy(mailAccounts = ids)
        saveOrderSoon("mailAccounts")
    }

    fun setFolderOrder(account: String, paths: List<String>) {
        val ids = accounts.orEmpty().map { it.id }
        order = order.copy(mail = (order.mail + (account to paths)).filterKeys { it in ids })
        saveOrderSoon("mail")
    }

    fun setHidden(account: String, paths: List<String>) {
        val ids = accounts.orEmpty().map { it.id }
        order = order.copy(mailHidden = (order.mailHidden + (account to paths)).filter { (k, v) -> k in ids && v.isNotEmpty() })
        saveOrderSoon("mailHidden")
    }

    // ---- Absenderbilder ----

    private suspend fun loadAvatars() {
        try {
            val got = api.avatars()
            avatarBook = got.book.ifBlank { "kontakte" }
            avatarIds.clear()
            for ((addr, id) in got.photos) if (id.isNotBlank()) avatarIds[addr.lowercase()] = id
        } catch (_: Exception) {
            // Älteres NAS oder keine Kontakte: nur Initialen.
        }
    }

    /** Das Bild des Kontakts zu einer Adresse, geholt beim ersten Bedarf. */
    fun photo(addr: String): ImageBitmap? {
        val key = addr.lowercase()
        photos[key]?.let { return it }
        val id = avatarIds[key] ?: return null
        if (autoload && photoAsked.add(key)) {
            scope.launch {
                val bytes = runCatching { api.contactPhoto(avatarBook, id) }.getOrNull() ?: return@launch
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { photos[key] = it.asImageBitmap() }
            }
        }
        return null
    }

    // ---- Listen ----

    fun list(account: String, folder: String): MailList =
        lists.getOrPut("$account\u0000$folder") { MailList(this, account, folder) }.also {
            while (lists.size > 8) lists.remove(lists.keys.first())
        }

    /** Alle offenen Listen sollen neu lesen (still, ohne Hinweis). */
    fun listsChanged() {
        listVersion++
    }

    /** Eine Änderung an Markierungen in jeder gehaltenen Liste nachtragen. */
    fun applyFlags(account: String, folder: String, uids: Collection<Long>, change: (Flags) -> Flags) {
        for (l in lists.values) l.patch(account, folder, uids, change)
    }

    fun forgetRows(account: String, folder: String, uids: Collection<Long>) {
        for (l in lists.values) l.drop(account, folder, uids)
    }

    // ---- Aktionen für eine oder mehrere Nachrichten ----

    /** Mails gruppiert nach Postfach und Ordner: jede Gruppe ist eine Anfrage. */
    private fun groups(items: List<MessageRow>, list: MailList?): Map<Pair<String, String>, List<Long>> =
        items.groupBy({ (it.account.ifEmpty { list?.account.orEmpty() }) to it.folder.ifEmpty { list?.folder.orEmpty() } }, { it.uid })

    suspend fun setFlag(items: List<MessageRow>, list: MailList?, flag: String, on: Boolean) {
        for ((key, uids) in groups(items, list)) {
            val (acc, folder) = key
            api.flags(acc, folder, uids, if (on) listOf(flag) else emptyList(), if (on) emptyList() else listOf(flag))
            applyFlags(acc, folder, uids) { f ->
                when (flag) {
                    "seen" -> f.copy(seen = on)
                    "flagged" -> f.copy(flagged = on)
                    else -> f
                }
            }
            if (flag == "seen") {
                val changed = items.count { (it.account.ifEmpty { acc }) == acc && it.flags.seen != on }
                adjust(acc, folder, unread = if (on) -changed else changed)
            }
            if (flag == "flagged") listsChanged()
        }
    }

    /** Verschieben bleibt in einem Postfach (die Web-Regel). */
    suspend fun move(items: List<MessageRow>, list: MailList?, to: String) {
        for ((key, uids) in groups(items, list)) {
            val (acc, folder) = key
            api.move(acc, folder, uids, to)
            forgetRows(acc, folder, uids)
            refreshFolders(acc)
        }
    }

    suspend fun archive(items: List<MessageRow>, list: MailList?): String {
        var to = ""
        for ((key, uids) in groups(items, list)) {
            val (acc, folder) = key
            to = api.archive(acc, folder, uids)
            forgetRows(acc, folder, uids)
            refreshFolders(acc)
        }
        return to
    }

    suspend fun junk(items: List<MessageRow>, list: MailList?) {
        for ((key, uids) in groups(items, list)) {
            val (acc, folder) = key
            val junk = folderOf(acc, "junk") ?: throw IllegalStateException("no junk folder")
            api.move(acc, folder, uids, junk.path)
            forgetRows(acc, folder, uids)
            refreshFolders(acc)
        }
    }

    suspend fun delete(items: List<MessageRow>, list: MailList?) {
        for ((key, uids) in groups(items, list)) {
            val (acc, folder) = key
            api.delete(acc, folder, uids)
            forgetRows(acc, folder, uids)
            refreshFolders(acc)
        }
    }

    fun inTrash(account: String, folder: String) = findFolder(account, folder)?.role == "trash"

    // ---- Live ----

    /** Folgt dem Strom, bis der Aufrufer abbricht; neu nach 1 s … 30 s. */
    suspend fun watch() {
        if (!autoload) return
        var attempt = 0
        var gap = false
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val opened = System.currentTimeMillis()
            val down = scope.launch {
                delay(SHOW_DOWN_AFTER)
                if (live != Live.Connected) live = Live.Down
            }
            api.events().collect { e ->
                when (e) {
                    MailEvent.Open -> {
                        down.cancel()
                        live = Live.Connected
                        if (gap) onEvent(MailEvent.Resync)
                        gap = false
                    }
                    else -> {
                        attempt = 0
                        onEvent(e)
                    }
                }
            }
            down.cancel()
            if (System.currentTimeMillis() - opened > 10_000) attempt = 0
            gap = true
            if (live == Live.Connected) live = Live.Off
            scope.launch {
                delay(SHOW_DOWN_AFTER)
                if (live != Live.Connected) live = Live.Down
            }
            delay(pauseFor(attempt++))
        }
    }

    fun onEvent(e: MailEvent) {
        when (e) {
            MailEvent.Resync -> {
                scope.launch { loadAccounts() }
                for (a in accounts.orEmpty()) refreshFolders(a.id, now = true)
                listsChanged()
            }
            is MailEvent.Status -> accounts = accounts?.map { if (it.id == e.account) it.copy(status = e.status) else it }
            is MailEvent.NewMail -> {
                refreshFolders(e.account)
                listsChanged()
            }
            is MailEvent.FlagsChanged -> {
                refreshFolders(e.account)
                listsChanged()
            }
            MailEvent.Open -> Unit
        }
    }

    // ---- Einstellungen ----

    var settings by mutableStateOf<MailSettings?>(null)

    /** Nur Bildtests ohne NAS ([autoload] aus): Nachrichten nach uid statt zu laden. */
    val previews = HashMap<Long, Message>()

    suspend fun loadSettings(): MailSettings = api.settings().also { settings = it }

    companion object {
        private const val QUIET = 3000L
        private const val MOST = 10_000L
        private const val SHOW_DOWN_AFTER = 8000L

        fun pauseFor(attempt: Int): Long = min(30_000L, 1000L shl attempt.coerceAtMost(5))
    }
}

/** Wo die Suche sucht: dieser Ordner oder alle Ordner des Postfachs. */
enum class Scope { Folder, All }

/**
 * Eine Liste (MessageList.svelte): Seiten zu 50, Filter, Suche in einem oder
 * allen Ordnern; über alle Postfächer mit Cursor.
 */
class MailList(private val model: MailModel, val account: String, val folder: String) {
    val unified: Boolean get() = account == ALL
    var rows by mutableStateOf<List<MessageRow>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var filters by mutableStateOf(Filters())
    var scope by mutableStateOf(Scope.Folder)
    var searched by mutableStateOf<SearchResult?>(null)
        private set
    var failed by mutableStateOf<List<UnifiedFailure>>(emptyList())
        private set
    private var nextUid = 0L
    private var cursor = ""
    val more: Boolean get() = if (unified) cursor.isNotEmpty() else nextUid > 0 && searched == null
    private var asked = 0
    var seenVersion = -1

    /** Liest die erste Seite neu ([quiet]: ohne „Wird geladen“, die Zeilen bleiben stehen). */
    suspend fun load(quiet: Boolean = false) {
        val n = ++asked
        if (!quiet) loading = true
        try {
            when {
                unified -> {
                    val page = model.api.unified(folder, filters)
                    if (n != asked) return
                    rows = page.messages
                    total = page.total
                    cursor = page.next
                    failed = page.failed
                    searched = null
                }
                scope == Scope.All && filters.any -> {
                    val got = model.api.search(account, filters)
                    if (n != asked) return
                    rows = got.messages.map { it.copy(account = account) }
                    total = got.total
                    searched = got
                    nextUid = 0
                }
                else -> {
                    val page = model.api.messages(account, folder, filters)
                    if (n != asked) return
                    rows = page.messages.map { it.copy(account = account, folder = folder) }
                    total = page.total
                    nextUid = page.next
                    searched = null
                }
            }
            error = null
            loaded = true
            model.refreshedAt = Instant.now()
        } catch (e: Exception) {
            if (n == asked) error = e
        } finally {
            if (n == asked) loading = false
        }
    }

    /** Die nächste Seite, wenn das Ende in Sicht kommt. */
    suspend fun loadMore() {
        if (loading || !more) return
        loading = true
        try {
            if (unified) {
                val page = model.api.unified(folder, filters, cursor)
                rows = rows + page.messages.filter { r -> rows.none { rowKey(it) == rowKey(r) } }
                cursor = page.next
                failed = page.failed
            } else {
                val page = model.api.messages(account, folder, filters, before = nextUid)
                rows = rows + page.messages.map { it.copy(account = account, folder = folder) }
                nextUid = page.next
            }
            error = null
        } catch (e: Exception) {
            error = e
        } finally {
            loading = false
        }
    }

    /** Nur für Bildtests und Vorschauen: eine Liste ohne NAS. */
    fun show(rows: List<MessageRow>, total: Int = rows.size, failed: List<UnifiedFailure> = emptyList(), searched: SearchResult? = null) {
        this.rows = rows
        this.total = total
        this.failed = failed
        this.searched = searched
        loaded = true
    }

    internal fun patch(acc: String, f: String, uids: Collection<Long>, change: (Flags) -> Flags) {
        if (rows.none { (it.account.ifEmpty { account }) == acc && it.folder.ifEmpty { folder } == f && it.uid in uids }) return
        rows = rows.map {
            if ((it.account.ifEmpty { account }) == acc && it.folder.ifEmpty { folder } == f && it.uid in uids) it.copy(flags = change(it.flags)) else it
        }
    }

    internal fun drop(acc: String, f: String, uids: Collection<Long>) {
        val before = rows.size
        rows = rows.filterNot { (it.account.ifEmpty { account }) == acc && it.folder.ifEmpty { folder } == f && it.uid in uids }
        total = (total - (before - rows.size)).coerceAtLeast(0)
    }
}
