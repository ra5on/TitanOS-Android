package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.UploadStatus
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream

/**
 * Lädt eine Datei in Stücken zu 8 MiB hoch, wie die Weboberfläche
 * (`CHUNK_BYTES`). Nach einem Fehler liest sie den Stand vom NAS und macht
 * dort weiter; [open] öffnet die Quelle neu und überspringt bis zum Stand.
 */
class Uploader(private val api: TitanApi, private val chunkBytes: Int = 8 * 1024 * 1024) {

    suspend fun upload(
        start: UploadStatus,
        open: () -> InputStream,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): UploadStatus = api.uploadFinish(send(start, open, progress).id)

    /**
     * Schickt alle Stücke, schließt aber nicht ab: Die Foto-Sicherung
     * schließt mit `app/photos/uploads/{id}/finish` und der Prüfsumme ab.
     */
    suspend fun send(
        start: UploadStatus,
        open: () -> InputStream,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): UploadStatus {
        var status = start
        val buf = ByteArray(chunkBytes)
        var attempts = 0
        while (status.offset < status.size) {
            try {
                open().use { input ->
                    skipFully(input, status.offset)
                    while (status.offset < status.size) {
                        val want = minOf(chunkBytes.toLong(), status.size - status.offset).toInt()
                        val n = readFully(input, buf, want)
                        if (n <= 0) throw IOException("source ended early")
                        status = api.uploadChunk(status.id, status.offset, buf, n)
                        attempts = 0
                        progress(status.offset, status.size)
                    }
                }
            } catch (e: ApiException) {
                when {
                    e.status == 503 && e.code == "upload_memory_pressure" -> delay(3000)
                    e.status == 423 -> throw e
                    e.status == 409 && e.code == "upload_mismatch" -> delay(500)
                    else -> throw e
                }
                status = api.uploadStatus(status.id)
            } catch (e: IOException) {
                if (++attempts > 5) throw e
                delay(2000L * attempts)
                status = api.uploadStatus(status.id)
            }
        }
        return status
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) throw IOException("source shorter than offset")
                left--
            } else {
                left -= n
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, want: Int): Int {
        var off = 0
        while (off < want) {
            val n = input.read(buf, off, want - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
