package io.github.ra5on.titanos.agent.ui

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.json
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Ein Einstellungsobjekt des NAS, roh gelesen und als Ganzes zurückgeschrieben
 * (PUT ersetzt alles): Felder, die die App nicht kennt, bleiben erhalten.
 * Änderungen gehen nach [wait] ms hinaus, wie die Effekte im Web.
 */
class Doc(
    private val model: AgentModel,
    private val path: String,
    private val defaults: JsonObject,
    private val scope: CoroutineScope,
    private val tr: Tr,
    private val wait: Long = 500,
    private val method: String = "PUT",
    private val saveBody: (JsonObject) -> JsonElement = { it },
) {
    var obj by mutableStateOf(defaults)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set
    private var job: Job? = null

    suspend fun load() {
        try {
            val got = model.api.getJson(path).obj()
            obj = JsonObject(defaults + got)
            loaded = true
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = problemText(tr, problemOf(e))
        }
    }

    operator fun get(key: String): JsonElement? = obj[key]

    /** Ändert Felder; mit [now] sofort, sonst nach der Pause. */
    fun set(vararg pairs: Pair<String, Any?>, now: Boolean = false) {
        obj = JsonObject(obj + pairs.associate { (k, v) -> k to json(v) })
        if (!loaded) return
        job?.cancel()
        job = scope.launch {
            if (!now) delay(wait)
            save()
        }
    }

    fun replace(next: JsonObject) {
        obj = next
        set(now = false)
    }

    suspend fun save(): Boolean {
        saving = true
        return try {
            val r = model.api.sendJson(method, path, saveBody(obj))
            (r as? JsonObject)?.takeIf { it.isNotEmpty() && it.keys.any { k -> k in obj } }?.let { obj = JsonObject(obj + it) }
            error = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = problemText(tr, problemOf(e))
            false
        } finally {
            saving = false
        }
    }
}

@Composable
fun rememberDoc(model: AgentModel, path: String, defaults: JsonObject = JsonObject(emptyMap()), wait: Long = 500, method: String = "PUT"): Doc {
    val scope = rememberCoroutineScope()
    val tr = tr()
    val doc = remember(path) { Doc(model, path, defaults, scope, tr, wait, method) }
    LaunchedEffect(path) { doc.load() }
    return doc
}

/** Ein Schieberegler in den Farben des Themas; [text] sagt den Wert in Worten. */
@Composable
fun Range(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, text: String, steps: Int = 0, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val c = Titan.colors
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        steps = steps,
        modifier = modifier.semantics { stateDescription = text },
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.track),
    )
}
