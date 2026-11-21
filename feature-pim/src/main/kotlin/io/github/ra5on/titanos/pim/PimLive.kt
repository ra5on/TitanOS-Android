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
                pause = 2_000L
                val reader = r.body.source()
                var event = ""
                val data = StringBuilder()
                while (coroutineContext.isActive) {
                    val line = reader.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            when (event) {
                                "pim" -> runCatching { TitanJson.parseToJsonElement(data.toString()).jsonObject["kind"]?.jsonPrimitive?.content }.getOrNull()?.let { emit(it) }
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
        onPauseOrDispose { scope.cancel() }
    }
}
