package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.MkdirRequest
import io.github.ra5on.titanos.model.PhotoAnswers
import io.github.ra5on.titanos.model.PhotoCheckRequest
import io.github.ra5on.titanos.model.PhotoFinishRequest
import io.github.ra5on.titanos.model.PhotoFinished
import io.github.ra5on.titanos.model.PhotoVerifyRequest
import io.github.ra5on.titanos.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Die Routen der Foto-Sicherung (TitanOS docs/ANDROID-APP.md „Foto-Sicherung“).
 * Sie brauchen die Sitzung der App; hochgeladen wird über [Uploader] und
 * [TitanApi] wie im Dateimanager.
 */
class PhotosApi(private val nas: NasConnection) {

    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    /** Welche Fotos liegen schon im Ziel (Freigabe + Ordner mit Unterordnern)? Höchstens 500 je Anfrage. */
    suspend fun check(request: PhotoCheckRequest): PhotoAnswers = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "app/photos/check")).post(jsonBody(request)).build() }.decode()
    }

    /** Bestätigt vor „Speicher freigeben“, dass die Dateien mit diesem Inhalt auf dem NAS liegen. */
    suspend fun verify(request: PhotoVerifyRequest): PhotoAnswers = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "app/photos/verify")).post(jsonBody(request)).build() }.decode()
    }

    /**
     * Schließt einen Upload ab; das NAS rechnet die Prüfsumme selbst. Bei
     * einem großen Video, das das NAS nach einem Neustart nachlesen muss,
     * dauert das, deshalb mit längerer Lesezeit.
     */
    suspend fun finish(id: String, sha256: String): PhotoFinished = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "app/photos/uploads/$id/finish")).post(jsonBody(PhotoFinishRequest(sha256))).build() }
            .decode<PhotoFinished>()
    }

    /** Legt einen Ordner an (eine Ebene). Gibt false zurück, wenn er schon da ist. */
    suspend fun mkdir(root: String, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            nas.execute { Request.Builder().url(url(it, "files/mkdir")).post(jsonBody(MkdirRequest(root, path))).build() }
                .use { it.throwIfError() }
            true
        } catch (e: ApiException) {
            if (e.status == 409 && e.code == "file_exists") false else throw e
        }
    }

    /** Eigene, nicht fertige Uploads in einem Ordner (nach einem Neustart des NAS). */
    suspend fun uploads(root: String, path: String): List<UploadStatus> = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "files/uploads", listOf("root" to root, "path" to path))).build() }.decode()
    }

    companion object {
        /** Lesezeit für den Abschluss: das NAS liest ein großes Video ggf. noch einmal. */
        const val FINISH_READ_MINUTES = 10L

        /** Eine Verbindung mit längerer Lesezeit für [finish]; sonst wie [base]. */
        fun forFinish(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
            base.newBuilder().readTimeout(FINISH_READ_MINUTES, TimeUnit.MINUTES).build()
    }
}
