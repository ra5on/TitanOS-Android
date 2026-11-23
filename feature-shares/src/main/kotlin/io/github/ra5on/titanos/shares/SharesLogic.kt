package io.github.ra5on.titanos.shares

import java.text.Collator
import java.util.Locale

// Regeln des Webs (ShareDialog.svelte, share-view.ts, share-delete.ts,
// shares-page.ts) ohne Oberfläche, damit sie sich prüfen lassen.

private val NAME = Regex("""^(?![ .])(?!.*[ .]$)(?!.*  )[\p{L}\p{N} ._-]{1,32}$""")
private val RESERVED = setOf("global", "homes", "printers", "print", "ipc")

/** Wie shares.ValidName: Buchstaben, Ziffern, Leerzeichen, - _ ., bis 32 Zeichen, keine reservierten Namen. */
fun validShareName(name: String): Boolean = NAME.matches(name) && name.lowercase(Locale.ROOT) !in RESERVED

fun clientLines(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** NFS-Client wie exportfs: IPv4/IPv6-Adresse oder -Netz oder Hostname (mit *). Das NAS prüft erneut. */
fun validClient(client: String): Boolean {
    if (!Regex("""^[A-Za-z0-9*][A-Za-z0-9.:/*_-]{0,99}$""").matches(client)) return false
    Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:/(\d{1,2}))?$""").find(client)?.let { m ->
        val g = m.groupValues
        return (1..4).all { g[it].toInt() <= 255 } && (g[5].isEmpty() || g[5].toInt() <= 32)
    }
    if (Regex("""^[\d./]+$""").matches(client)) return false
    if (client.contains(':')) {
        val parts = client.split('/')
        if (parts.size > 2) return false
        val prefix = parts.getOrNull(1)
        if (prefix != null && !(Regex("""^\d{1,3}$""").matches(prefix) && prefix.toInt() <= 128)) return false
        val halves = parts[0].split("::")
        if (halves.size > 2) return false
        val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
        return groups.all { Regex("""^[0-9A-Fa-f]{1,4}$""").matches(it) } &&
            (if (halves.size == 2) groups.size < 8 else groups.size == 8)
    }
    if (client.contains('/')) return false
    return client.split('.').all { Regex("""^[A-Za-z0-9*_](?:[A-Za-z0-9*_-]{0,61}[A-Za-z0-9*_])?$""").matches(it) }
}

/** visibleShares: Filter nach Volume, Protokoll, Suchwörtern; sortiert wie das Web (Zahlen natürlich). */
fun visibleShares(shares: List<Share>, view: ShareView, query: String, locale: Locale): List<Share> {
    val words = query.lowercase(locale).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val collator = Collator.getInstance(locale)
    val natural = Comparator<String> { a, b -> naturalCompare(a, b, collator) }
    val byName = compareBy(natural) { s: Share -> s.name }
    val cmp = if (view.sort == "volume") compareBy(natural) { s: Share -> s.volume }.then(byName) else byName
    return shares.filter { s ->
        (view.volume.isEmpty() || s.volume == view.volume) &&
            (view.protocol == "all" || (if (view.protocol == "smb") s.smb else s.nfs.enabled)) &&
            words.all { w -> "${s.name} ${s.volume}".lowercase(locale).contains(w) }
    }.sortedWith(if (view.descending) cmp.reversed() else cmp)
}

/** Vergleich mit Zahlen als Zahlen (`localeCompare(…, {numeric: true})`). */
fun naturalCompare(a: String, b: String, collator: Collator): Int {
    val ra = Regex("""\d+|\D+""").findAll(a).map { it.value }.toList()
    val rb = Regex("""\d+|\D+""").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            collator.compare(x, y)
        }
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}

/** Adressen einer Freigabe je System (shareAddresses); Label sind Schlüssel des Webs. */
fun shareAddresses(share: Share, host: String): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    val enc = java.net.URLEncoder.encode(share.name, "UTF-8").replace("+", "%20")
    if (share.smb) {
        list += "shares.windows" to "\\\\$host\\${share.name}"
        list += "shares.mac" to "smb://$host/$enc"
        list += "shares.linux" to "smb://$host/$enc"
        list += "shares.linuxMount" to "//$host/${share.name}"
    }
    if (share.nfs.enabled) list += "shares.nfs" to "$host:/${share.name}"
    return list
}

private val USAGE_KINDS = setOf(
    "vm", "docker", "docker-unknown", "backup-source", "backup-target", "share", "smb-session", "smb-unknown", "mount",
    "nested-subvolume", "subvolume-snapshots", "subvolume-unknown", "unsafe-path", "purge-running", "volume-snapshots", "count-limit",
)

/** Text einer Verwendung, die das Löschen der Daten verhindert oder warnt; Unbekanntes allgemein. */
fun usageKey(kind: String): String = if (kind in USAGE_KINDS) "share.use.$kind" else "share.use.other"

/** Löschen darf erst nach einer Vorschau ohne Hindernisse beginnen. */
fun canDeleteData(preview: DataPreview?): Boolean = preview != null && preview.blockers.isEmpty()

/** Fortschritt beim Löschen der Dateien in Prozent; vorher unbekannt. */
fun deletionPercent(job: ShareDeletion): Double? =
    if (job.phase != "deleting" || job.totalFiles <= 0) null else (job.files.toDouble() / job.totalFiles * 100).coerceIn(0.0, 100.0)

/** Kein Zugang für irgendwen: SMB an, kein Gast, keine Konten (Samba bietet sie niemandem an). */
fun nobodyCanUse(share: Share): Boolean = !share.guest && share.smb && share.access.isEmpty()

/** Entwurf des Formulars (draft im Web). */
data class ShareDraft(
    val name: String = "",
    val volume: String = "",
    val smb: Boolean = true,
    val nfs: String = "off",
    val clients: String = "",
    val access: Map<String, String> = emptyMap(),
    val guest: Boolean = false,
    val browseable: Boolean = true,
    val recycle: Boolean = false,
    val days: String = "30",
    val timeMachine: Boolean = false,
    val sizeGB: String = "0",
    val previousVersions: Boolean = false,
    val guard: Boolean = false,
    val quotaGB: String = "0",
) {
    val quotaValue: Double get() = quotaGB.replace(',', '.').toDoubleOrNull() ?: 0.0

    /** Unter 16 MiB nimmt das NAS keine Grenze an. */
    val quotaTooSmall: Boolean get() = quotaValue > 0 && quotaValue * 1e9 < 16 * 1024 * 1024
    val badClients: List<String> get() = if (nfs == "off") emptyList() else clientLines(clients).filterNot(::validClient)
    val noClients: Boolean get() = nfs != "off" && clientLines(clients).isEmpty()
    val nobody: Boolean get() = smb && !guest && access.isEmpty()

    fun body(): ShareBody = ShareBody(
        name = name,
        volume = volume,
        smb = smb,
        nfs = NfsBody(enabled = nfs != "off", mode = nfs, clients = clientLines(clients)),
        access = access,
        guest = smb && guest,
        browseable = browseable,
        recycle = Recycle(enabled = smb && recycle, days = days.toIntOrNull()?.takeIf { it != 0 } ?: 30),
        timeMachine = TimeMachine(enabled = smb && timeMachine, sizeGB = sizeGB.toLongOrNull() ?: 0),
        previousVersions = smb && previousVersions,
        guard = smb && guard,
        quota = Math.round(quotaValue * 1e9),
    )

    companion object {
        /** Neue Freigabe: wer sie anlegt, darf sie nutzen; sonst die Werte der Freigabe. */
        fun of(share: Share?, volumes: List<String>, me: String): ShareDraft = if (share == null) {
            ShareDraft(volume = volumes.firstOrNull() ?: "", access = if (me.isNotEmpty()) mapOf(me to "rw") else emptyMap())
        } else {
            ShareDraft(
                name = share.name,
                volume = share.volume,
                smb = share.smb,
                nfs = if (share.nfs.enabled) (share.nfs.mode?.takeIf { it.isNotEmpty() && it != "off" } ?: "rw") else "off",
                clients = (share.nfs.clients ?: emptyList()).joinToString("\n"),
                access = share.access,
                guest = share.guest,
                browseable = share.browseable,
                recycle = share.recycle.enabled,
                days = share.recycle.days.toString(),
                timeMachine = share.timeMachine.enabled,
                sizeGB = share.timeMachine.sizeGB.toString(),
                previousVersions = share.previousVersions ?: false,
                guard = share.guard ?: false,
                quotaGB = share.quota?.takeIf { it > 0 }?.let { formatQuota(Math.round(it / 1e7) / 100.0) } ?: "0",
            )
        }

        private fun formatQuota(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}

/** Reiter, auf dem das Feld steht, das eine Ablehnung des NAS betrifft (ERROR_TABS). */
fun errorTab(code: String, draft: ShareDraft): String? = when (code) {
    "invalid_share_name", "share_exists", "unknown_volume", "volume_change", "invalid_nfs_client" -> "general"
    "unknown_user", "invalid_access", "share_guard_locked" -> "access"
    "invalid_option" -> if (draft.quotaValue > 0 && !draft.smb) "general" else "options"
    else -> null
}?.takeIf { it == "general" || draft.smb }
