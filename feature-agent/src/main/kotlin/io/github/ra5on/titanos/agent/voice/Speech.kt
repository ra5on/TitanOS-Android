package io.github.ra5on.titanos.agent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

/** Klartext aus dem Markdown einer Antwort: ohne Tabellen, Code und Links (speakable im Web). */
fun speakable(markdown: String): String = markdown
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("(?m)^\\s*\\|.*\\|\\s*$"), " ")
    .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("[*_`#>~]+"), "")
    .replace(Regex("(?m)^\\s*[-+]\\s+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Ein WAV mit 16 kHz, mono, 16 Bit (toWav16k im Web), wie es die Sprachdienste des NAS nehmen. */
fun wav16k(pcm: ByteArray, rate: Int = 16000): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()); header.putInt(36 + pcm.size); header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(1)
    header.putInt(rate); header.putInt(rate * 2); header.putShort(2); header.putShort(16)
    header.put("data".toByteArray()); header.putInt(pcm.size)
    return header.array() + pcm
}

/**
 * Vorlesen und Spracheingabe wie im Web (agent-speech.svelte.ts, Composer):
 * mit den Sprachdiensten des NAS (`/agent/speech/say`, `/agent/speech/hear`
 * oder Whisper über `/agent/transcribe`), sonst mit dem, was das Handy kann
 * (TextToSpeech, Spracherkennung von Android).
 */
class Speech(private val context: Context, private val scope: CoroutineScope) {
    var speakingId by mutableStateOf("")
        private set
    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set
    /** Lautstärke des Mikrofons 0 … 1 (für die Figur des Sprechmodus). */
    var level by mutableFloatStateOf(0f)
        private set

    var rate = 1.0
    var tr: Tr = Tr.Empty
    var model: AgentModel? = null

    /** Die App fragt nach dem Mikrofon und meldet das Ergebnis. */
    var askMic: ((Boolean) -> Unit) -> Unit = { it(hasMic()) }
    /** Die App startet die Spracherkennung von Android; das Ergebnis kommt in [heardFromDevice]. */
    var launchRecognizer: () -> Unit = {}

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var sayJob: Job? = null
    private var record: AudioRecord? = null
    private var recordJob: Job? = null
    private val pcm = ByteArrayOutputStream()

    val deviceHear: Boolean =
        runCatching { context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }.getOrDefault(false)

    init {
        runCatching {
            tts = TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (speakingId == id) speakingId = "" }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { if (speakingId == id) speakingId = "" }
            })
        }
    }

    fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Hört das NAS zu (Sprachdienste oder Whisper)? */
    fun nasHear(m: AgentModel) = m.speechHear || m.status?.voice == true

    fun canHear(m: AgentModel) = nasHear(m) || deviceHear

    fun canSpeak(m: AgentModel) = m.speechSay || ttsReady || tts != null

    // ---------- Vorlesen ----------

    /** Liest einen Text vor; derselbe noch einmal hält an. */
    fun speak(id: String, markdown: String) {
        val again = speakingId == id
        stop()
        if (again) return
        val text = speakable(markdown)
        if (text.isEmpty()) return
        val m = model
        if (m != null && m.speechSay) {
            speakingId = id
            sayJob = scope.launch {
                try {
                    val (bytes, _) = m.api.postForBytes("agent/speech/say", jsonOf("text" to text.take(4000)))
                    val file = withContext(Dispatchers.IO) { File(context.cacheDir, "say.audio").apply { writeBytes(bytes) } }
                    if (speakingId != id) return@launch
                    player = MediaPlayer().apply {
                        setDataSource(file.path)
                        setOnCompletionListener { if (speakingId == id) speakingId = ""; release(); if (player === this) player = null }
                        setOnErrorListener { _, _, _ -> if (speakingId == id) speakingId = ""; true }
                        prepare()
                        start()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (speakingId == id) speakingId = ""
                }
            }
            return
        }
        val engine = tts ?: return
        engine.language = if (tr.de) Locale.GERMANY else Locale.UK
        engine.setSpeechRate(rate.toFloat().coerceIn(0.5f, 2f))
        speakingId = id
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) speakingId = ""
    }

    fun stop() {
        sayJob?.cancel()
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { tts?.stop() }
        speakingId = ""
    }

    // ---------- Spracheingabe ----------

    /** Mikrofon: antippen an/aus oder (Einstellung „halten“) gedrückt halten. */
    fun micDown(m: AgentModel) {
        if (recording) { finishInput(m); return }
        if (!nasHear(m)) { launchRecognizer(); return }
        askMic { ok -> if (ok) start() else m.say(micText(tr)) }
    }

    fun micUp(m: AgentModel) {
        if (m.look.mic == "hold" && recording) finishInput(m)
    }

    /** Das Gesagte von der Erkennung des Handys kommt ins Feld (nie von selbst gesendet). */
    fun heardFromDevice(m: AgentModel, said: String?) {
        val s = said?.trim().orEmpty()
        if (s.isNotEmpty()) m.draft = if (m.draft.isBlank()) s else m.draft.trimEnd() + " " + s
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording || !hasMic()) return false
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)) }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        pcm.reset()
        record = rec
        rec.startRecording()
        recording = true
        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(1024)
            val bytes = ByteArray(2048)
            while (isActive && recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i]
                    sum += (s / 32768.0) * (s / 32768.0)
                    bytes[2 * i] = (s.toInt() and 0xff).toByte()
                    bytes[2 * i + 1] = (s.toInt() shr 8 and 0xff).toByte()
                }
                synchronized(pcm) { pcm.write(bytes, 0, 2 * n) }
                level = (sqrt(sum / n) * 9).toFloat().coerceIn(0f, 1f)
                rms = sqrt(sum / n)
            }
        }
        return true
    }

    /** Lautstärke als RMS (0 … 1) für die Erkennung von Sprechpausen. */
    @Volatile var rms = 0.0
        private set

    /** Hält die Aufnahme an und gibt sie als WAV zurück (leer, wenn nichts da ist). */
    fun stopRecording(): ByteArray {
        if (!recording) return ByteArray(0)
        recording = false
        runCatching { record?.stop(); record?.release() }
        record = null
        level = 0f
        val raw = synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
        return if (raw.isEmpty()) raw else wav16k(raw)
    }

    /** Neue Aufnahme ohne das Mikrofon loszulassen (Sprechmodus): verwirft, was da ist. */
    fun restartBuffer() = synchronized(pcm) { pcm.reset() }

    fun takeBuffer(): ByteArray = synchronized(pcm) { val raw = pcm.toByteArray(); pcm.reset(); if (raw.isEmpty()) raw else wav16k(raw) }

    private fun finishInput(m: AgentModel) {
        val wav = stopRecording()
        if (wav.size < 44 + 3200) return
        transcribing = true
        scope.launch {
            try {
                val said = transcribe(m, wav)
                if (said.isNotEmpty()) m.draft = if (m.draft.isBlank()) said else m.draft.trim() + " " + said
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                m.say(problemText(tr, problemOf(e)))
            } finally {
                transcribing = false
            }
        }
    }

    /** Das NAS macht Text daraus: seine Sprachdienste (WAV) oder Whisper. */
    suspend fun transcribe(m: AgentModel, wav: ByteArray): String {
        val path = if (m.speechHear) "agent/speech/hear" else "agent/transcribe"
        return m.api.upload(path, wav, "audio/wav", listOf("lang" to tr.lang)).obj().str("text").trim()
    }

    fun release() {
        stop()
        stopRecording()
        runCatching { tts?.shutdown() }
        tts = null
    }
}

/** Ohne Mikrofon: der Text des Webs nennt den Browser; die App sagt, wo es in Android erlaubt wird. */
fun micText(tr: Tr): String = if (tr.de) "Kein Zugriff auf das Mikrofon. Erlaube es der Agent-App in den Android-Einstellungen › Apps › Agent › Berechtigungen." else "No access to the microphone. Allow it for the Agent app in Android settings › Apps › Agent › Permissions."
