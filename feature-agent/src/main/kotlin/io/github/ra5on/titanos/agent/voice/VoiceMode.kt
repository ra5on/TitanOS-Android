package io.github.ra5on.titanos.agent.voice

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.AgentWarm
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.FloatButton
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Phasen des Sprechmodus (VoiceMode.svelte). */
enum class Phase(val key: String) { Starting("starting"), Listening("listening"), Hearing("hearing"), Reading("reading"), Thinking("thinking"), Speaking("speaking"), Paused("paused") }

private const val START = 0.035
private const val QUIET_MS = 1300L
private const val MIN_MS = 350L
private const val MAX_MS = 30_000L
private const val OVER = 0.09
private const val OVER_MS = 260L
private const val FREE_MS = 9000L

private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("[^a-z0-9 ]"), "").trim()

/** Ein Buchstabe darf abweichen (near im Web). */
fun near(a: String, b: String): Boolean {
    if (a == b) return true
    if (abs(a.length - b.length) > 1 || b.length < 4) return false
    var i = 0; var j = 0; var off = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++ } else {
            if (++off > 1) return false
            when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
        }
    }
    return off + (a.length - i) + (b.length - j) <= 1
}

/** Was vom Gesagten gesendet wird: alles, der Teil nach dem Weckwort oder nichts. */
fun afterWake(wake: String, said: String, free: Boolean): Pair<String, Boolean> {
    if (wake.isEmpty()) return said to false
    val want = plain(wake).split(" ").filter { it.isNotEmpty() }
    val words = said.split(Regex("\\s+"))
    for (at in 0 until min(3, words.size)) {
        val got = words.drop(at).take(want.size).map(::plain)
        if (got.size == want.size && got.indices.all { near(got[it], want[it]) }) {
            val rest = words.drop(at + want.size).joinToString(" ").replace(Regex("^[\\s,.:;!?-]+"), "")
            return if (rest.isNotEmpty()) rest to false else "" to true
        }
    }
    return (if (free) said else "") to false
}

private const val WAKE_KEY = "talk.wake"

/**
 * Sprechmodus (VoiceMode.svelte): ein Gespräch ohne Tippen. Das Mikrofon
 * bleibt offen; was gesagt wird, schneidet die Lautstärke aus (Sprache über
 * dem Rauschen, Ende nach einer Pause), das NAS macht Text daraus, er geht
 * als Nachricht hinaus; die Antwort wird vorgelesen, dann hört er wieder zu.
 * Wer über die Antwort spricht, unterbricht sie (Ins-Wort-Fallen). Wahlweise
 * mit Weckwort. Ohne Sprachdienst am NAS: die Spracherkennung des Handys.
 */
@Composable
fun VoiceMode(model: AgentModel, speech: Speech, onClose: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val calm = LocalCalm.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agent-talk", Context.MODE_PRIVATE) }
    var phase by remember { mutableStateOf(Phase.Starting) }
    var heard by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    var wake by remember { mutableStateOf(prefs.getString(WAKE_KEY, "").orEmpty()) }
    var wakeDraft by remember { mutableStateOf(wake.ifEmpty { "Titan" }) }
    var freeUntil by remember { mutableLongStateOf(0L) }
    var asked by remember { mutableStateOf(0) }
    val nas = speech.nasHear(model)
    BackHandler(onBack = onClose)

    fun setWake(on: Boolean) {
        wake = if (on) wakeDraft.trim().ifEmpty { "Titan" } else ""
        wakeDraft = wake.ifEmpty { wakeDraft }
        prefs.edit().putString(WAKE_KEY, wake).apply()
    }

    suspend fun understand(wav: ByteArray) {
        try {
            val said = speech.transcribe(model, wav)
            if (said.replace(Regex("[^\\p{L}\\p{N}]"), "").length < 2) { phase = Phase.Listening; return }
            val (text, waiting) = afterWake(wake, said, System.currentTimeMillis() < freeUntil)
            if (text.isEmpty()) {
                if (waiting) freeUntil = System.currentTimeMillis() + FREE_MS
                phase = Phase.Listening
                return
            }
            heard = text; answer = ""; problem = ""
            asked = model.current?.entries?.size ?: 0
            phase = Phase.Thinking
            model.send(text) { ok -> if (!ok) { problem = tr.t("agent.talk.notSent"); phase = Phase.Listening } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = problemText(tr, problemOf(e))
            phase = Phase.Listening
        }
    }

    // Das Mikrofon und die Erkennung der Sprechpausen.
    LaunchedEffect(nas) {
        if (!nas) { problem = tr.t("agent.talk.noNas").let { if (it == "agent.talk.noNas") tr.t("agent.set.whisperOff") else it }; phase = Phase.Paused; return@LaunchedEffect }
        var granted: Boolean? = null
        speech.askMic { granted = it }
        while (granted == null) delay(50)
        if (granted != true || !speech.start()) { problem = micText(tr); phase = Phase.Paused; return@LaunchedEffect }
        phase = Phase.Listening
        var noise = 0.01
        var began = 0L
        var lastLoud = 0L
        var overSince = 0L
        while (isActive) {
            delay(33)
            val rms = speech.rms
            val now = System.currentTimeMillis()
            when (phase) {
                Phase.Listening -> {
                    noise = noise * 0.97 + rms * 0.03
                    if (rms > max(START, noise * 3)) { speech.restartBuffer(); began = now; lastLoud = now; phase = Phase.Hearing }
                    else speech.restartBuffer()
                }
                Phase.Hearing -> {
                    if (rms > max(START * 0.6, noise * 2)) lastLoud = now
                    if (now - lastLoud > QUIET_MS || now - began > MAX_MS) {
                        phase = Phase.Reading
                        val wav = speech.takeBuffer()
                        if (now - began < MIN_MS || wav.isEmpty()) phase = Phase.Listening else understand(wav)
                    }
                }
                Phase.Speaking -> {
                    if (speech.speakingId.isEmpty()) { freeUntil = now + FREE_MS; phase = Phase.Listening }
                    else if (rms > max(OVER, noise * 5)) {
                        if (overSince == 0L) overSince = now
                        if (now - overSince > OVER_MS) {
                            overSince = 0; speech.stop(); freeUntil = now + FREE_MS
                            speech.restartBuffer(); began = now; lastLoud = now; phase = Phase.Hearing
                        }
                    } else overSince = 0
                }
                else -> speech.restartBuffer()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { speech.stopRecording(); speech.stop() } }

    // Der Lauf endete: die Antwort vorlesen, oder anhalten, wo die Person entscheiden muss.
    val busy = model.busy
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        val ended = wasBusy && !busy
        wasBusy = busy
        if (!ended || phase != Phase.Thinking) return@LaunchedEffect
        val chat = model.current
        if (chat?.pending != null) { problem = tr.t("agent.talk.pending"); phase = Phase.Paused; return@LaunchedEffect }
        val last = chat?.entries?.drop(asked)?.lastOrNull { it.role == "assistant" && !it.content.isNullOrEmpty() }
        if (last == null) { phase = Phase.Listening; return@LaunchedEffect }
        answer = speakable(last.content.orEmpty())
        phase = Phase.Speaking
        speech.speak("talk-" + last.id, last.content.orEmpty())
        delay(300)
        if (speech.speakingId.isEmpty()) phase = Phase.Listening
    }

    fun tap() {
        when (phase) {
            Phase.Speaking -> { speech.stop(); phase = Phase.Listening }
            Phase.Paused -> { problem = ""; phase = Phase.Listening }
            Phase.Listening -> phase = Phase.Paused
            Phase.Hearing -> phase = Phase.Listening
            else -> Unit
        }
    }

    val figure = when (phase) {
        Phase.Thinking, Phase.Reading, Phase.Starting -> "think"
        Phase.Speaking -> "speak"
        Phase.Paused -> "rest"
        else -> "listen"
    }
    Box(
        Modifier.fillMaxSize().background(c.bg).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = tr.t("agent.talk.title") },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { FloatButton("close", tr.t("agent.talk.end"), onClose) }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val tapLabel = tr.t("agent.talk.tap.${phase.key}")
                Box(Modifier.size(260.dp).clickable(role = Role.Button, onClick = ::tap).semantics { contentDescription = tapLabel }) {
                    VoiceOrb(figure, speech.level, calm, Modifier.fillMaxSize())
                }
                Text(tr.t("agent.talk.${phase.key}"), style = AgentText.lg.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = c.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (problem.isNotEmpty()) Text(problem, style = AgentText.md, color = c.warnTextCompat(), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.fillMaxWidth().weight(0.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (heard.isNotEmpty()) Text(heard, style = AgentText.lg, color = c.muted, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                if (answer.isNotEmpty()) Text(answer, style = AgentText.lg.copy(lineHeight = 24.sp), color = c.text)
                if (heard.isEmpty() && problem.isEmpty()) Text(if (wake.isNotEmpty()) tr.t("agent.talk.hintWake", "word" to wake) else tr.t("agent.talk.hint"), style = AgentText.md, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(wake.isNotEmpty(), { setWake(it) }, label = tr.t("agent.talk.wake"))
                Text(tr.t("agent.talk.wake"), style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                Field(wakeDraft, { wakeDraft = it.take(24); if (wake.isNotEmpty()) setWake(true) }, Modifier.width(130.dp), label = tr.t("agent.talk.wakeWord"))
            }
            PillButton(tr.t("agent.talk.end"), onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun io.github.ra5on.titanos.ui.generated.TitanColors.warnTextCompat(): Color = if (dark) statusWarn else warnText

/**
 * Die Figur des Sprechmodus (VoiceOrb.svelte), auf 300 × 300 Einheiten:
 * Schein, Skala, drei Ringe, 96 Balken (zuhören: die Lautstärke, sprechen:
 * eine Stimme, denken: eine wandernde Welle), ein Kern, beim Denken Funken.
 */
@Composable
fun VoiceOrb(figure: String, level: Float, calm: Boolean, modifier: Modifier = Modifier) {
    val bars = remember { FloatArray(96) }
    val state = remember { FloatArray(6).also { it[1] = 1f; it[2] = 0.5f; it[4] = 1.6f } } // energy, speed, glow, sparks, pace, phase
    var turn by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(calm) {
        if (calm) return@LaunchedEffect
        var last = -1L
        while (isActive) withInfiniteAnimationFrameNanos { now ->
            if (last >= 0) {
                val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                time += dt
                turn += dt * state[1]
                state[5] += dt * state[4]
            }
            last = now
        }
    }
    Canvas(modifier) {
        val t = time
        val w = size.minDimension
        val cx = size.width / 2; val cy = size.height / 2
        val u = w / 300f
        val wantSpeed = if (figure == "think") 3.4f else if (figure == "speak") 1.3f else if (figure == "rest") 0.25f else 1f
        val wantGlow = if (figure == "rest") 0.22f else if (figure == "think") 0.75f else 0.6f
        val k = if (calm) 1f else 0.06f
        state[1] += (wantSpeed - state[1]) * k
        state[2] += (wantGlow - state[2]) * k
        state[3] += ((if (figure == "think") 1f else 0f) - state[3]) * k
        state[4] += ((if (figure == "think") 5f else 1.6f) - state[4]) * k
        val glow = state[2]
        var sum = 0f
        for (i in bars.indices) {
            val a = (i / 96f) * 2 * PI.toFloat()
            val want = when (figure) {
                "listen" -> 0.05f + level * 0.5f * (0.6f + 0.4f * sin(a * 5 + t * 6).let { it * it })
                "speak" -> if (calm) 0.45f else {
                    val talk = 0.55f + 0.45f * sin(t * 7.3f) * sin(t * 2.1f + 1)
                    max(0f, 0.5f + 0.3f * sin(a * 3 + t * 5) + 0.2f * sin(a * 7 - t * 8) + 0.15f * sin(a * 13 + t * 13)) * max(0.15f, talk)
                }
                "think" -> if (calm) 0.2f else 0.12f + 0.2f * max(0f, sin(a * 2 - t * 4)).pow(6)
                else -> 0.04f
            }
            bars[i] += (want - bars[i]) * (if (calm) 1f else if (want > bars[i]) 0.5f else 0.14f)
            sum += bars[i]
        }
        val loud = min(1f, (sum / 96f) * 2.4f)
        state[0] += (loud - state[0]) * (if (calm) 1f else 0.2f)
        val energy = state[0]
        val ink = { a: Float -> AgentWarm.copy(alpha = a.coerceIn(0f, 1f)) }
        val light = { a: Float -> Color(min(1f, AgentWarm.red + 38 / 255f), min(1f, AgentWarm.green + 90 / 255f), min(1f, AgentWarm.blue + 110 / 255f), a.coerceIn(0f, 1f)) }
        val center = Offset(cx, cy)
        drawCircle(Brush.radialGradient(0f to ink(0.3f * glow + 0.25f * energy), 0.45f to ink(0.1f * glow + 0.08f * energy), 1f to ink(0f), center = center, radius = 150 * u), 150 * u, center)
        for (i in 0 until 60) {
            val a = (i / 60f) * 2 * PI.toFloat() + turn * 0.05f
            val long = i % 5 == 0
            val r1 = 139 * u; val r0 = r1 - (if (long) 9 else 4) * u
            drawLine(ink((if (long) 0.6f else 0.28f) * (0.4f + glow)), Offset(cx + cos(a) * r0, cy + sin(a) * r0), Offset(cx + cos(a) * r1, cy + sin(a) * r1), 1.2f * u, StrokeCap.Round)
        }
        fun ring(radius: Float, parts: Int, sweep: Float, rate: Float, width: Float, a: Float) {
            for (i in 0 until parts) {
                val from = turn * rate + (i / parts.toFloat()) * 2 * PI.toFloat()
                drawArc(ink(a * (0.35f + glow)), Math.toDegrees(from.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false,
                    topLeft = Offset(cx - radius * u, cy - radius * u), size = Size(2 * radius * u, 2 * radius * u), style = Stroke(width * u, cap = StrokeCap.Round))
            }
        }
        ring(124f, 2, 1.9f, 0.35f, 2.4f, 0.85f)
        ring(113f, 3, 1.05f, -0.6f, 1.4f, 0.6f)
        ring(103f, 24, 0.12f, 0.9f, 3.2f, 0.42f)
        val base = 62 * u + energy * 5 * u
        for (i in bars.indices) {
            val a = (i / 96f) * 2 * PI.toFloat() - PI.toFloat() / 2
            val len = (3 + bars[i] * 34) * u
            drawLine(ink(0.45f + bars[i] * 0.55f), Offset(cx + cos(a) * base, cy + sin(a) * base), Offset(cx + cos(a) * (base + len), cy + sin(a) * (base + len)), 2.3f * u, StrokeCap.Round)
        }
        val beat = if (calm) 0f else sin(state[5]) * 0.03f
        val core = (38 + energy * 12) * u * (1 + beat)
        drawCircle(Brush.radialGradient(0f to ink(0.35f), 1f to ink(0f), center = center, radius = core * 1.9f), core * 1.9f, center)
        drawCircle(
            Brush.radialGradient(0f to light(0.95f * (0.45f + glow)), 0.55f to ink(0.92f * (0.45f + glow)), 1f to ink(0.5f * (0.35f + glow)), center = Offset(cx - core * 0.25f, cy - core * 0.3f), radius = core),
            core, center,
        )
        drawCircle(light(0.5f * glow), core + 5 * u, center, style = Stroke(1.2f * u))
        if (state[3] > 0.01f) for (s in 0 until 3) for (q in 0 until 10) {
            val a = turn * 1.5f + (s / 3f) * 2 * PI.toFloat() - q * 0.07f
            drawCircle(light((1 - q / 10f) * 0.9f * state[3]), (3.2f - q * 0.25f) * u, Offset(cx + cos(a) * 52 * u, cy + sin(a) * 52 * u))
        }
    }
}
