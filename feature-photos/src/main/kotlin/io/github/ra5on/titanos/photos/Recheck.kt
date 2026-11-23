package io.github.ra5on.titanos.photos

import io.github.ra5on.titanos.model.PhotoItem
import kotlinx.coroutines.CancellationException

/**
 * Nachprüfung im Lauf (1.03): Was die App einmal als gesichert gemerkt hat,
 * fragt sie nach und nach wieder beim NAS nach (`app/photos/verify`). Bis 1.02
 * galt eine am NAS gelöschte Aufnahme am Handy für immer als „gesichert“; nur
 * „Speicher freigeben“ fragte vor dem Löschen nach.
 *
 * Je Lauf höchstens [RECHECK_MAX] Aufnahmen (so viele nimmt das NAS je
 * Anfrage), die am längsten nicht bestätigten zuerst, und keine, die das NAS in
 * den letzten [RECHECK_AFTER_MS] bestätigt hat: so ist alles alle paar Tage
 * einmal nachgeprüft, ohne das NAS zu belasten.
 *
 * Nur ein klares „missing“ macht aus „gesichert“ ein „fehlt auf dem NAS“; ein
 * „unsure“, eine fehlende Antwort oder ein Fehler (Netz, NAS 503
 * `photos_unavailable`) ändert nichts. Sagt das NAS später wieder „saved“
 * (Freigabe war kurz weg, Datei zurückkopiert), gilt die Aufnahme wieder als
 * gesichert.
 */
const val RECHECK_MAX = 500

/** Nach so langer Zeit fragt die Sicherung eine bestätigte Aufnahme wieder nach. */
const val RECHECK_AFTER_MS = 3L * 24 * 60 * 60 * 1000

/** Eine nachzuprüfende Aufnahme: ihre Nummer am Handy, ihr Stand und ob sie schon als fehlend gilt. */
data class Recheck(val id: Long, val saved: Saved, val missing: Boolean)

/** Wofür dieser Lauf das NAS fragt: nur Aufnahmen, die noch auf dem Handy sind ([present]). */
fun recheckWindow(state: StateFile, present: Set<Long>, now: Long, max: Int = RECHECK_MAX): List<Recheck> {
    val all = state.saved.map { (id, s) -> Recheck(id, s, false) } + state.missing.map { (id, s) -> Recheck(id, s, true) }
    return all.asSequence()
        .filter { it.id in present && now - it.saved.verifiedAt >= RECHECK_AFTER_MS }
        .sortedWith(compareBy({ it.saved.verifiedAt }, { it.id }))
        .take(max)
        .toList()
}

/**
 * Die Antworten des NAS ([answers]: Prüfsumme → `saved`, `missing`, `unsure`)
 * in den Stand einarbeiten.
 *  - `saved`: gesichert und bestätigt (eine fehlende kommt zurück).
 *  - `missing`: fehlt auf dem NAS; wird nicht neu hochgeladen.
 *  - alles andere: unverändert, beim nächsten Lauf wieder gefragt.
 */
fun applyRecheck(state: StateFile, asked: List<Recheck>, answers: Map<String, String>, now: Long): StateFile {
    var saved = state.saved
    var missing = state.missing
    var dismissed = state.missingDismissed
    for (r in asked) {
        when (answers[r.saved.sha256]) {
            "saved" -> {
                missing = missing - r.id
                dismissed = dismissed - r.id
                saved = saved + (r.id to r.saved.copy(verifiedAt = now))
            }
            "missing" -> {
                saved = saved - r.id
                missing = missing + (r.id to r.saved.copy(verifiedAt = now))
            }
            else -> Unit
        }
    }
    return state.copy(saved = saved, missing = missing, missingDismissed = dismissed)
}

/**
 * Gesicherte Aufnahmen, die nicht mehr auf dem Handy sind (Speicher
 * freigegeben): nichts nachzuprüfen, aber als angesehen merken, damit die
 * Prüfung alle sechs Stunden ([recheckDue]) nicht ihretwegen einen Lauf anstößt.
 */
fun touchAbsent(state: StateFile, present: Set<Long>, now: Long): StateFile {
    fun touch(m: Map<Long, Saved>) = m.mapValues { (id, s) -> if (id !in present && now - s.verifiedAt >= RECHECK_AFTER_MS) s.copy(verifiedAt = now) else s }
    return state.copy(saved = touch(state.saved), missing = touch(state.missing))
}

/**
 * Eine Nachprüfung im Lauf: das Fenster fragen ([verify]: höchstens 500
 * Prüfsummen, Antwort Prüfsumme → Zustand) und die Antworten einarbeiten.
 * Scheitert die Anfrage (Netz, NAS 503 `photos_unavailable`, sonst ein
 * Fehler), bleibt der Stand, wie er war. True, wenn sich etwas geändert hat.
 */
suspend fun recheckSaved(store: BackupStore, present: Set<Long>, now: Long, verify: suspend (List<PhotoItem>) -> Map<String, String>): Boolean {
    store.update { touchAbsent(it, present, now) }
    val window = recheckWindow(store.snapshot(), present, now)
    if (window.isEmpty()) return false
    val asked = window.map { PhotoItem(it.saved.sha256, it.saved.size) }.distinctBy { it.sha256 }
    val answers = try {
        verify(asked)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return false
    }
    val before = store.snapshot()
    store.update { applyRecheck(it, window, answers, now) }
    return store.snapshot().missing != before.missing
}

/** Ob eine Nachprüfung fällig ist (die Prüfung alle sechs Stunden stößt dann einen Lauf an). */
fun recheckDue(state: StateFile, now: Long): Boolean =
    (state.saved.values.asSequence() + state.missing.values.asSequence()).any { now - it.verifiedAt >= RECHECK_AFTER_MS }

/** „Erneut sichern“: die fehlenden Aufnahmen [ids] kommen beim nächsten Lauf wieder dran. */
fun resaveMissing(state: StateFile, ids: Set<Long>): StateFile =
    state.copy(missing = state.missing - ids, missingDismissed = state.missingDismissed - ids)

/** „Nicht mehr sichern“: die fehlenden Aufnahmen [ids] auf die Liste der ausgelassenen. */
fun ignoreMissing(state: StateFile, ids: Set<Long>): StateFile =
    state.copy(
        missing = state.missing - ids,
        ignored = state.ignored + state.missing.filterKeys { it in ids },
        missingDismissed = state.missingDismissed - ids,
    )

/** „Ausblenden“: das Symbol oben warnt nicht mehr wegen dieser fehlenden Aufnahmen. */
fun dismissMissing(state: StateFile, ids: Set<Long>): StateFile =
    state.copy(missingDismissed = state.missingDismissed + ids.filter { it in state.missing })

/** In den Einstellungen zurückgenommen: die ausgelassenen Aufnahmen werden wieder gesichert. */
fun unignoreAll(state: StateFile): StateFile = state.copy(ignored = emptyMap())

/** Fehlende Aufnahmen, die noch auf dem Handy sind, und ob das Symbol oben ihretwegen warnt. */
data class MissingView(val photos: Int = 0, val videos: Int = 0, val warn: Boolean = false) {
    val count: Int get() = photos + videos
}

fun missingView(state: StateFile, present: Map<Long, Kind>): MissingView {
    val ids = state.missing.keys.filter { it in present }
    return MissingView(
        photos = ids.count { present[it] != Kind.Video },
        videos = ids.count { present[it] == Kind.Video },
        warn = ids.any { it !in state.missingDismissed },
    )
}
