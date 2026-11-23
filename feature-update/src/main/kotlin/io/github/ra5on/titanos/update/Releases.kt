package io.github.ra5on.titanos.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Die Apps, die sich selbst aktualisieren, und wie ihre Dateien im Release
 * heißen (release.yml: `<Präfix><versionName>.apk`, dazu `SHA256SUMS`). Die
 * Agent-App (`TitanOS-Agent-`) gibt es ab 1.01 nicht mehr; ihr Agent steckt
 * in der TitanOS-App, ihre letzte Fassung 1.00 bleibt auf GitHub.
 */
enum class AppKind(val packageName: String, val assetPrefix: String) {
    TitanOS("io.github.ra5on.titanos", "TitanOS-Android-"),
    /** Die Uhr-App (Wear OS, PLAN 116 Paket G). */
    Watch("io.github.ra5on.titanos.watch", "TitanOS-Watch-"),
    ;

    companion object {
        /** Die App, die gerade läuft; sie aktualisiert sich selbst. */
        fun own(packageName: String): AppKind = entries.firstOrNull { it.packageName == packageName } ?: TitanOS

        /** Die alte Agent-App (bis 1.00): wird nicht mehr aktualisiert, darf deinstalliert werden. */
        const val OLD_AGENT_PACKAGE = "io.github.ra5on.titanos.agent"
    }
}

/** Eine Version auf GitHub: Tag vX.Y.0 → angezeigt X.YY, versionCode X·100+Y. */
data class AppRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val prerelease: Boolean,
    val notes: String,
    /** Datei je App; fehlt eine, gibt es sie in diesem Release nicht. */
    val apks: Map<AppKind, String>,
    val sums: String?,
    val sizes: Map<AppKind, Long>,
)

@Serializable
private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

/**
 * Liest die Releases von GitHub (ohne Anmeldung, über HTTPS mit der normalen
 * Zertifikatsprüfung). Vertraut wird ihnen erst nach der Prüfung in
 * [Installer]: Prüfsumme aus SHA256SUMS und Signaturzertifikat der APK
 * gleich dem der installierten App – Android selbst lässt ohnehin kein
 * Update mit anderem Schlüssel zu.
 */
class ReleaseSource(
    private val client: OkHttpClient,
    private val base: HttpUrl = "https://api.github.com/repos/ra5on/TitanOS-Android/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun releases(): List<AppRelease> = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment("releases").addQueryParameter("per_page", "20").build()
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub ${r.code}")
            json.decodeFromString<List<GhRelease>>(r.body.string()).filter { !it.draft }.mapNotNull(::parse)
        }
    }

    /** Die neueste Version über [installedCode]; Testfassungen nur mit [includeTest]. */
    suspend fun newest(installedCode: Int, includeTest: Boolean, kind: AppKind = AppKind.TitanOS): AppRelease? =
        pick(releases(), installedCode, includeTest, kind)

    companion object {
        private val TAG = Regex("""^v(\d+)\.(\d+)\.0$""")

        fun versionOf(tag: String): Pair<String, Int>? {
            val m = TAG.matchEntire(tag) ?: return null
            val major = m.groupValues[1].toInt()
            val minor = m.groupValues[2].toInt()
            if (minor > 99) return null
            return "%d.%02d".format(major, minor) to major * 100 + minor
        }

        /**
         * Die Prüfsumme von [name] aus einer Datei SHA256SUMS, wie `sha256sum`
         * sie schreibt (`<hex>  <name>`, im Binärmodus `<hex> *<name>`); null, wenn sie fehlt.
         */
        fun sumIn(sums: String, name: String): String? = sums.lines().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].trimStart('*') == name }?.get(0)?.takeIf { it.length == 64 }

        fun pick(all: List<AppRelease>, installedCode: Int, includeTest: Boolean, kind: AppKind): AppRelease? =
            all.filter { it.versionCode > installedCode && it.apks.containsKey(kind) && (includeTest || !it.prerelease) }
                .maxByOrNull { it.versionCode }

        private fun parse(r: GhRelease): AppRelease? {
            val (name, code) = versionOf(r.tag) ?: return null
            val apks = HashMap<AppKind, String>()
            val sizes = HashMap<AppKind, Long>()
            for (kind in AppKind.entries) {
                r.assets.firstOrNull { it.name == "${kind.assetPrefix}$name.apk" }?.let {
                    apks[kind] = it.url
                    sizes[kind] = it.size
                }
            }
            return AppRelease(
                r.tag, name, code, r.prerelease, plainNotes(r.body.orEmpty()), apks,
                r.assets.firstOrNull { it.name == "SHA256SUMS" }?.url, sizes,
            )
        }

        /** Versionshinweise ohne Markdown-Zeichen, für die Anzeige als Text. */
        fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
            line.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                .replace(Regex("""`([^`]+)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace(Regex("""^#+\s*"""), "")
                .replace(Regex("""^(\s*)[-*] """), "$1• ")
        }.trim()
    }
}
