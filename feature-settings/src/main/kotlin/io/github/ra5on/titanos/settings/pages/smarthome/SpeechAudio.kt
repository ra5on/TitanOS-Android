package io.github.ra5on.titanos.settings.pages.smarthome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.settings.common.SettingsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Ton für die Seite Sprachdienste: eine Stimme anhören (Antwort von
// /speech/say direkt aus dem Speicher, keine Datei) und das Mikrofon
// ausprobieren (16 kHz, mono, 16 Bit als WAV an /speech/hear).

/** Spielt Audio-Bytes ab, ohne sie auf das Handy zu schreiben. */
internal class BytesPlayer {
    private var player: MediaPlayer? = null

    fun play(bytes: ByteArray, onEnd: () -> Unit) {
        stop()
        val source = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(size.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                return n
            }
            override fun getSize(): Long = bytes.size.toLong()
            override fun close() {}
        }
        player = MediaPlayer().apply {
            setDataSource(source)
            setOnCompletionListener { onEnd(); release(); if (player === this) player = null }
            setOnErrorListener { _, _, _ -> onEnd(); true }
            prepare()
            start()
        }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}

/** Nimmt vom Mikrofon auf, bis [stop] kommt. */
internal class MicRecorder {
    private var record: AudioRecord? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false
    private var thread: Thread? = null

    companion object {
        fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        if (running || !allowed(context)) return false
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)) }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        synchronized(pcm) { pcm.reset() }
        record = rec
        rec.startRecording()
        running = true
        thread = Thread {
            val buf = ByteArray(4096)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    /** Hält an und gibt die Aufnahme als WAV zurück (leer, wenn nichts da ist). */
    fun stop(): ByteArray {
        if (!running) return ByteArray(0)
        running = false
        runCatching { thread?.join(500) }
        runCatching { record?.stop(); record?.release() }
        record = null
        val raw = synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
        return if (raw.isEmpty()) raw else wav16k(raw)
    }
}

/** Ein WAV-Kopf vor 16-Bit-PCM, 16 kHz, mono (toWav16k im Web). */
internal fun wav16k(pcm: ByteArray): ByteArray {
    val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
    b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
    return b.array()
}

/**
 * Schickt rohe Bytes mit eigenem Typ (wie `apiFetch(..., body: wav)` im Web).
 * [SettingsApi] kann nur JSON und multipart; bis es dort eine Methode dafür
 * gibt, nimmt diese Hilfe dieselbe Verbindung (gleiche Sitzung, gleiche
 * Adressen, keine eigenen Zugangsdaten).
 */
internal suspend fun SettingsApi.postRaw(path: String, bytes: ByteArray, mime: String): JsonElement = withContext(Dispatchers.IO) {
    val field = SettingsApi::class.java.getDeclaredField("nas").apply { isAccessible = true }
    val nas = field.get(this@postRaw) as NasConnection
    nas.execute { base ->
        Request.Builder().url(base.resolve("/api/v1/${path.removePrefix("/")}") ?: error("bad path"))
            .post(bytes.toRequestBody(mime.toMediaType())).build()
    }.use { r ->
        r.throwIfError()
        val text = r.body.string()
        if (text.isBlank()) kotlinx.serialization.json.JsonNull else SettingsApi.JSON.parseToJsonElement(text)
    }
}
