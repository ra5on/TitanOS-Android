package io.github.ra5on.titanos.settings.pages.network

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tabs
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Der Zustand der Netzwerk-Seite (routes/settings/network/+page.svelte):
 * was das NAS meldet, der Entwurf und die Probe. Fehler beim Abfragen
 * während einer Probe sind kein Abbruch: der letzte Stand bleibt, der
 * Countdown läuft weiter, die Seite fragt weiter nach.
 */
@Stable
internal class NetModel(private val api: SettingsApi, private val scope: CoroutineScope, private val tr: () -> Tr) {
    var status by mutableStateOf<JsonObject?>(null)
    var modes by mutableStateOf(listOf("active-backup", "802.3ad", "balance-alb"))
    var timezone by mutableStateOf("")
    var draft by mutableStateOf<JsonObject?>(null)
    var error by mutableStateOf("")
    var busy by mutableStateOf(false)
    var lost by mutableStateOf(false)

    /** Die letzte Abfrage blieb ohne Antwort (nur, wenn schon ein Stand da ist). */
    var silent by mutableStateOf(false)
    var loadError by mutableStateOf<Throwable?>(null)

    /** Bestätigen kam nicht über die neue Verwaltungsadresse an. */
    var confirmElsewhere by mutableStateOf(false)

    /** Restzeit der Probe in Sekunden, zählt lokal herunter. */
    var left by mutableLongStateOf(0)

    /** Offene Editoren: solange behält die Seite den Entwurf. */
    var editors by mutableIntStateOf(0)
    private var changes = 0

    val current: JsonObject? get() = status?.let { s -> (s["pendingTopology"] as? JsonObject) ?: s.o("topology") }
    val pending: Boolean get() = status?.get("pending") is JsonObject
    val dirty: Boolean get() = draft != null && current != null && draft != current
    val locked: Boolean get() = busy || pending
    val ports: List<JsonObject> get() = status.objs("interfaces")

    private fun take(r: JsonObject) {
        val keep = dirty || editors > 0
        status = r.o("network")
        if (r.has("bondModes")) modes = r.strings("bondModes")
        if (r.has("timezone")) timezone = r.str("timezone")
        if (!keep) draft = current
        if (!pending) {
            lost = false
            confirmElsewhere = false
        }
        left = status.long("pendingSeconds")
    }

    /** Ein Stand, der vor „Testen“, „Behalten“ oder „Zurück“ erfragt wurde, ist älter als deren Antwort und ersetzt sie nicht. */
    suspend fun load(asked: Boolean = false) {
        val before = changes
        try {
            val answer = api.obj("network")
            if (asked || (before == changes && !busy)) take(answer)
            silent = false
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Während einer Probe ist das NAS hier unter Umständen nicht erreichbar.
            if (status == null) loadError = e else silent = true
        }
    }

    fun reload() = scope.launch { load() }

    fun reset() {
        draft = current
        error = ""
    }

    fun apply(context: Context) = Guard.require(context, GuardAction.Network) {
        val d = draft ?: return@require
        scope.launch {
            busy = true
            changes++
            error = ""
            lost = false
            val body = networkPayload(d)
            draft = body
            // Mit einer neuen Adresse kommt die Antwort auf diese Anfrage nie an.
            val answer = CompletableDeferred<JsonElement>()
            scope.launch {
                try {
                    answer.complete(api.put("network", body))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    answer.cancel()
                    throw e
                } catch (e: Exception) {
                    answer.completeExceptionally(e)
                }
            }
            try {
                val res = withTimeoutOrNull(8000) { answer.await() }
                if (res == null) {
                    lost = true
                    scope.launch { runCatching { (answer.await() as? JsonObject)?.let { take(it) } } }
                } else {
                    status = (res as? JsonObject).o("network")
                    left = status.long("pendingSeconds")
                    draft = current
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.unreachable()) lost = true else error = tr().errorOf(e, detail = true)
            } finally {
                busy = false
            }
        }
    }

    fun settle(context: Context, kind: String) = Guard.require(context, GuardAction.Network) {
        scope.launch {
            busy = true
            changes++
            error = ""
            try {
                val res = api.post("network/$kind") as? JsonObject
                status = res.o("network")
                left = status.long("pendingSeconds")
                draft = current
                confirmElsewhere = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Während sich Bonds oder VLANs einrichten, kann die Antwort auf
                // „Bestätigen“ abreißen, nachdem das NAS gespeichert hat: es zählt der Stand jetzt.
                if (kind == "confirm" && !(e is ApiException && e.status >= 400)) {
                    load(asked = true)
                    if (status != null && !pending) {
                        draft = current
                        return@launch
                    }
                }
                if (kind == "confirm" && e is ApiException && e.code == "invalid_network") confirmElsewhere = true
                error = tr().errorOf(e, detail = true)
            } finally {
                busy = false
            }
        }
    }

    fun setHostname(name: String) {
        draft = draft?.with("hostname" to name)
        reload()
    }
}

/**
 * Einstellungen › Netzwerk (routes/settings/network): benannte Netze
 * (Übersicht), Anschlüsse und Bonds, Diagnose und Fernzugriff. Jede Änderung
 * ist ein Entwurf, bis sie auf Probe getestet und bestätigt ist.
 */
@Composable
fun NetworkPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val m = remember { NetModel(env.api, scope) { tr } }
    val tabs = listOf("overview", "interfaces", "diagnostics", "remote")
    val tab = a.tab?.takeIf { it in tabs } ?: "overview"
    var diagnose by remember { mutableStateOf("") }

    LaunchedEffect(m) {
        m.load()
        while (true) {
            delay(3000)
            if (!m.busy) m.load()
        }
    }
    // Der Countdown läuft lokal jede Sekunde; jede Antwort stellt ihn neu.
    LaunchedEffect(m.pending) {
        while (m.pending) {
            delay(1000)
            if (m.left > 0) m.left = m.left - 1
        }
    }
    // Ungetestete Änderungen gehen nicht stillschweigend verloren.
    BackHandler(enabled = m.dirty && !m.pending && !ov.open) {
        ov.confirm(tr.t("netx.unsaved"), tr.t("netx.discardText"), action = tr.t("netcfg.discard"), cancel = tr.t("common.cancel"), danger = true) {
            m.reset()
        }
    }

    PageIntro(t("netx.intro"))
    val status = m.status
    if (status == null) {
        m.loadError?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { m.reload() }) }
            ?: LoadingLine(t("common.loading"))
        return
    }
    if (m.pending) {
        TrialBox(
            title = t("netx.trial", "time" to countdown(m.left)),
            hint = t("netx.confirmHint"),
            confirmLabel = t("netx.confirm"),
            revertLabel = t("netx.revert"),
            busy = m.busy,
            onConfirm = { m.settle(context, "confirm") },
            onRevert = { m.settle(context, "revert") },
        ) {
            if (m.silent) Hint(stringResource(if (m.left > 0) R.string.net_trial_retrying else R.string.net_trial_over))
            val mgmt = managementOf(status["pendingTopology"] as? JsonObject)
            if (managementChanges(status) || m.confirmElsewhere) {
                Hint(stringResource(if (m.confirmElsewhere) R.string.net_confirm_elsewhere else R.string.net_trial_address))
                val url = mgmt?.let { managementURL(it) }.orEmpty()
                if (url.isNotEmpty()) {
                    Mono(url)
                    SmallButton(t("netx.openManagement"), { openUrl(context, url) })
                }
            }
        }
    } else if (m.dirty) {
        ActionBox(t("netx.unsaved"), t("netcfg.trial")) {
            SmallButton(t("netcfg.discard"), { m.reset() }, enabled = !m.busy)
            SmallButton(t(if (m.busy) "netcfg.applying" else "netx.test"), { m.apply(context) }, kind = ButtonKind.Primary, enabled = !m.busy)
        }
    }
    if (status.has("notApplied")) {
        Banner(t("netcfg.notApplied") + "\n" + status.str("notApplied"), tone = Tone.Warn)
    }
    if (m.lost) Banner(t("netcfg.lost"), tone = Tone.Warn)
    Note(m.error)

    val draft = m.draft ?: return
    Tabs(tab, tabs.map { it to t("netx.tab.$it") }, { a.tab = it })
    when (tab) {
        "overview" -> NetworkOverview(m, a) { id ->
            // Aus der Zeile eines Netzes: „Diagnose“ wählt es und prüft gleich.
            diagnose = id
            a.tab = "diagnostics"
        }
        "interfaces" -> NetworkInterfaces(m, draft)
        "diagnostics" -> NetworkDiagnostics(
            networks = if (m.pending) emptyList() else status.o("topology").objs("networks"),
            pending = m.pending,
            selected = diagnose,
            onSelect = { diagnose = it },
        )
        "remote" -> RemoteTab(a)
    }
}

/** Ob ein Netz schon zu dem gehört, was das NAS betreibt (nur dann hat es einen Live-Zustand). */
internal fun NetModel.applied(n: JsonObject): Boolean = current.objs("networks").any { it.str("id") == n.str("id") }

/** Der Entwurf mit einem neuen oder geänderten Netz; ein bevorzugtes Netz nimmt den anderen die Markierung. */
internal fun saveNetwork(draft: JsonObject, n: JsonObject, isNew: Boolean): JsonObject {
    val networks = draft.objs("networks").map { other ->
        if (n.bool("management") && other.str("id") != n.str("id")) other.with("management" to false) else other
    }
    val next = if (isNew) networks + n else networks.map { if (it.str("id") == n.str("id")) n else it }
    return draft.with("networks" to next)
}

internal fun saveBond(draft: JsonObject, b: JsonObject, isNew: Boolean): JsonObject {
    val bonds = draft.objs("bonds")
    return draft.with("bonds" to if (isNew) bonds + b else bonds.map { if (it.str("id") == b.str("id")) b else it })
}
