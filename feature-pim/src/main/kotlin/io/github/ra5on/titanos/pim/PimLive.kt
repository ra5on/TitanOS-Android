package io.github.ra5on.titanos.pim

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * Live-Strom der Webseiten (`GET /calendar/stream`, api/pim_live.go): je
 * Änderung eines Kalenders, Adressbuchs oder einer Erinnerungsliste die Art
 * („calendar“, „contacts“, „tasks“), nach verlorenen Ereignissen „resync“.
 * Bricht die Verbindung ab, verbindet er nach einer Pause neu.
 */
fun pimChanges(stream: NasConnection): Flow<String> = flow {
    var pause = 2_000L
    while (coroutineContext.isActive) {
        try {
            stream.execute { Request.Builder().url(it.newBuilder().encodedPath("/api/v1/calendar/stream").build()).header("Accept", "text/event-stream").build() }.use { r ->
                r.throwIfError()
                android.util.Log.d("TitanSync", "live stream connected")
                pause = 2_000L
                val reader = r.body.source()
                var event = ""
                val data = StringBuilder()
                while (coroutineContext.isActive) {
                    val line = reader.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            when (event) {
                                "pim" -> {
                                    android.util.Log.d("TitanSync", "live: $data")
                                    runCatching { TitanJson.parseToJsonElement(data.toString()).jsonObject["kind"]?.jsonPrimitive?.content }.getOrNull()?.let { emit(it) }
                                }
                                "resync" -> emit("resync")
                            }
                            event = ""; data.clear()
                        }
                        line.startsWith(":") -> {}
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trim()) }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        delay(pause)
        pause = (pause * 2).coerceAtMost(60_000L)
    }
}.flowOn(Dispatchers.IO)

/**
 * Solange die App sichtbar ist: jede Änderung am NAS stößt den Abgleich der
 * betroffenen Art an (Einstellung „Änderungen am NAS sofort holen“).
 */
@androidx.compose.runtime.Composable
fun PimLiveSync(stream: NasConnection) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.lifecycle.compose.LifecycleResumeEffect(stream) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
        val p = io.github.ra5on.titanos.pim.sync.SyncStore(context).load()
        if (p.liveWhileOpen && io.github.ra5on.titanos.pim.sync.PimAccount.current(context) != null) {
            scope.launch {
                pimChanges(stream).collect { kind ->
                    val authority = when (kind) {
                        "contacts" -> android.provider.ContactsContract.AUTHORITY
                        "calendar" -> android.provider.CalendarContract.AUTHORITY
                        "resync" -> null
                        else -> return@collect
                    }
                    io.github.ra5on.titanos.pim.sync.PimSync.requestNow(context, manual = false, only = authority)
                }
            }
        }
        // Änderungen in Kontakte/Kalender des Handys (andere Apps) gleich hochladen, solange TitanOS offen ist;
        // im Hintergrund stößt Android den Abgleich an bzw. der Zeitplan.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val observers = mutableListOf<android.database.ContentObserver>()
        if (p.uploadOnChange && io.github.ra5on.titanos.pim.sync.PimAccount.current(context) != null) {
            for ((uri, authority) in listOf(
                android.provider.ContactsContract.RawContacts.CONTENT_URI to android.provider.ContactsContract.AUTHORITY,
                android.provider.CalendarContract.Events.CONTENT_URI to android.provider.CalendarContract.AUTHORITY,
            )) {
                val o = object : android.database.ContentObserver(handler) {
                    private var pending = false
                    override fun onChange(selfChange: Boolean, changed: android.net.Uri?) {
                        // Eigene Schreibvorgänge des Abgleichs tragen caller_is_syncadapter.
                        if (changed?.getQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER) == "true" || pending) return
                        pending = true
                        handler.postDelayed({
                            pending = false
                            if (hasDirty(context, authority)) io.github.ra5on.titanos.pim.sync.PimSync.requestNow(context, manual = false, only = authority)
                        }, 3000)
                    }
                }
                runCatching { context.contentResolver.registerContentObserver(uri, true, o) }.onSuccess { observers += o }
            }
        }
        onPauseOrDispose {
            scope.cancel()
            observers.forEach { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        }
    }
}

/** Gibt es im Konto Zeilen, die am Handy geändert und noch nicht hochgeladen sind? */
private fun hasDirty(context: android.content.Context, authority: String): Boolean = runCatching {
    val a = io.github.ra5on.titanos.pim.sync.PimAccount.current(context) ?: return false
    val (uri, sel) = if (authority == android.provider.ContactsContract.AUTHORITY) {
        android.provider.ContactsContract.RawContacts.CONTENT_URI to "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${android.provider.ContactsContract.RawContacts.ACCOUNT_NAME}=? AND (${android.provider.ContactsContract.RawContacts.DIRTY}=1 OR ${android.provider.ContactsContract.RawContacts.DELETED}=1)"
    } else {
        android.provider.CalendarContract.Events.CONTENT_URI to "${android.provider.CalendarContract.Events.ACCOUNT_TYPE}=? AND ${android.provider.CalendarContract.Events.ACCOUNT_NAME}=? AND (${android.provider.CalendarContract.Events.DIRTY}=1 OR ${android.provider.CalendarContract.Events.DELETED}=1)"
    }
    context.contentResolver.query(uri, arrayOf("_id"), sel, arrayOf(a.type, a.name), null)?.use { it.count > 0 } ?: false
}.getOrDefault(false)
