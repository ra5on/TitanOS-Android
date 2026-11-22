package io.github.ra5on.titanos.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Was gerade passiert; die Seite „Über die App“ liest es. */
sealed interface UpdateStep {
    data object Idle : UpdateStep
    data object Checking : UpdateStep
    data class Downloading(val kind: AppKind, val done: Long, val total: Long) : UpdateStep
    data class Installing(val kind: AppKind) : UpdateStep
    /** Android wartet auf „Installieren“ im eigenen Dialog. */
    data class NeedsConfirm(val kind: AppKind) : UpdateStep
    data class Failed(val reason: String) : UpdateStep
}

/** Gemeinsamer Zustand für Seite, Worker und Empfänger (eine App, ein Prozess). */
object UpdateState {
    var step: UpdateStep by mutableStateOf(UpdateStep.Idle)
    var available: AppRelease? by mutableStateOf(null)
    var agentAvailable: AppRelease? by mutableStateOf(null)
    var lastCheckMs: Long by mutableStateOf(0L)
    var lastError: String? by mutableStateOf(null)
}

/** Einstellungen des Selbst-Updates. Testfassungen ab Werk, solange die App selbst eine ist. */
class UpdateSettings(context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val installedCode = Installer.versionCode(context, AppKind.own(context.packageName))

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    var includeTest: Boolean
        get() = prefs.getBoolean("test", installedCode in 1..99)
        set(v) = prefs.edit().putBoolean("test", v).apply()
}

/**
 * Lädt eine neue Version, prüft sie und übergibt sie dem PackageInstaller.
 * Geprüft wird: SHA-256 gegen SHA256SUMS des Releases, Paketname,
 * versionCode höher als installiert und das Signaturzertifikat gleich dem der
 * installierten TitanOS-App. Nur dann bekommt Android die Datei.
 */
class Installer(private val context: Context, private val client: OkHttpClient) {

    suspend fun install(release: AppRelease, kind: AppKind) {
        val url = release.apks[kind] ?: throw IOException("no ${kind.name} in ${release.tag}")
        val sumsUrl = release.sums ?: throw IOException("no SHA256SUMS in ${release.tag}")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "${kind.assetPrefix}${release.versionName}.apk")
        val expected = withContext(Dispatchers.IO) { sumFor(sumsUrl, apk.name) }
        UpdateState.step = UpdateStep.Downloading(kind, 0, release.sizes[kind] ?: 0)
        val actual = withContext(Dispatchers.IO) { download(url, apk, kind) }
        if (!actual.equals(expected, ignoreCase = true)) {
            apk.delete()
            throw IOException("checksum")
        }
        verifyArchive(apk, kind, release.versionCode)
        UpdateState.step = UpdateStep.Installing(kind)
        withContext(Dispatchers.IO) { commit(apk, kind) }
    }

    private fun sumFor(url: String, name: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("SHA256SUMS ${r.code}")
            return r.body.string().lines().map { it.trim().split(Regex("\\s+"), limit = 2) }
                .firstOrNull { it.size == 2 && it[1].trimStart('*') == name }?.get(0)
                ?: throw IOException("$name not in SHA256SUMS")
        }
    }

    private fun download(url: String, to: File, kind: AppKind): String {
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download ${r.code}")
            val total = r.body.contentLength()
            var done = 0L
            r.body.byteStream().use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        UpdateState.step = UpdateStep.Downloading(kind, done, total)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Paketname, höhere Version und dasselbe Signaturzertifikat wie diese App. */
    private fun verifyArchive(apk: File, kind: AppKind, code: Int) {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            ?: throw IOException("not an APK")
        if (info.packageName != kind.packageName) throw IOException("package ${info.packageName}")
        if (info.longVersionCode.toInt() != code || code <= versionCode(context, kind)) throw IOException("version ${info.longVersionCode}")
        val theirs = certs(info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty())
        if (theirs.isEmpty() || theirs != ownCerts(context)) throw IOException("signature")
    }

    private fun commit(apk: File, kind: AppKind) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(kind.packageName)
            // Die eigene App ohne Rückfrage, wenn Android es zulässt (Handy oder Uhr).
            if (kind.packageName == context.packageName) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(context, InstallReceiver::class.java).putExtra(InstallReceiver.KIND, kind.name)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        fun versionCode(context: Context, kind: AppKind): Int = runCatching {
            context.packageManager.getPackageInfo(kind.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)

        fun versionName(context: Context, kind: AppKind): String? = runCatching {
            context.packageManager.getPackageInfo(kind.packageName, 0).versionName
        }.getOrNull()

        /** Darf die App Pakete installieren („Unbekannte Apps installieren“)? */
        fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

        fun allowInstallIntent(context: Context) =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun certs(raw: List<ByteArray>): Set<String> = raw.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        }.toSet()

        fun ownCerts(context: Context): Set<String> {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            return certs(info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty())
        }
    }
}

/** Antwort des PackageInstallers: fertig, Rückfrage von Android nötig oder gescheitert. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = runCatching { AppKind.valueOf(intent.getStringExtra(KIND).orEmpty()) }.getOrDefault(AppKind.TitanOS)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                UpdateState.step = UpdateStep.NeedsConfirm(kind)
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Im Vordergrund gleich der Dialog von Android, sonst eine Mitteilung zum Antippen.
                val started = runCatching { context.startActivity(confirm) }.isSuccess
                if (!started) Notifier.confirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateState.step = UpdateStep.Idle
                if (kind == AppKind.Agent) UpdateState.agentAvailable = null else UpdateState.available = null
            }
            else -> UpdateState.step = UpdateStep.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install")
        }
    }

    companion object {
        const val KIND = "kind"
    }
}

/** Mitteilungen des Updates (Kanal „Updates“). */
object Notifier {
    private const val CHANNEL = "updates"
    private const val ID_AVAILABLE = 7_116_100
    private const val ID_CONFIRM = 7_116_101

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.upd_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun allowed(context: Context) =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun available(context: Context, release: AppRelease, open: Intent) {
        if (!allowed(context)) return
        channel(context)
        val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.upd_note_title, release.versionName))
            .setContentText(context.getString(R.string.upd_note_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { NotificationManagerCompat.from(context).notify(ID_AVAILABLE, n) }
    }

    fun confirm(context: Context, confirm: Intent) {
        if (!allowed(context)) return
        channel(context)
        val pi = PendingIntent.getActivity(context, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.upd_confirm_title))
            .setContentText(context.getString(R.string.upd_confirm_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { NotificationManagerCompat.from(context).notify(ID_CONFIRM, n) }
    }
}
