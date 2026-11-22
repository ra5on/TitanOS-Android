package io.github.ra5on.titanos.watch.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Sprache auf der Uhr. Diktat über die Spracheingabe des Systems, wenn es
 * eine gibt; sonst nimmt die Uhr selbst auf und Whisper am NAS schreibt mit.
 * Vorlesen mit der Stimme der Uhr oder mit Piper am NAS.
 */
object Voice {
    fun recognizerIntent(prompt: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    fun hasRecognizer(context: Context): Boolean =
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

    const val RATE = 16_000

    /** Nimmt auf, bis [keepGoing] false wird oder [maxMillis] um sind; liefert WAV (16 kHz, mono, 16 Bit). */
    @SuppressLint("MissingPermission")
    suspend fun record(keepGoing: () -> Boolean, maxMillis: Long = 15_000): ByteArray = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        val pcm = ByteArrayOutputStream()
        try {
            rec.startRecording()
            val buf = ByteArray(min)
            val end = System.currentTimeMillis() + maxMillis
            while (coroutineContext.isActive && keepGoing() && System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        wav(pcm.toByteArray())
    }

    fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }
}

/** Vorlesen: die Stimme der Uhr, wenn es eine gibt, sonst (oder auf Wunsch) Piper am NAS. */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready: Boolean? = null
    private var player: MediaPlayer? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.getDefault())?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
        }
    }

    /** true, wenn die Uhr selbst gesprochen hat. */
    fun speakLocal(text: String): Boolean {
        if (ready != true) return false
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
        return true
    }

    suspend fun speakNas(api: WatchApi, text: String) {
        val audio = api.say(text)
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "say.audio").apply { writeBytes(audio) }
        }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        player?.release()
        player = null
    }
}
