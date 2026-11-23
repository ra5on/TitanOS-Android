package io.github.ra5on.titanos.ui.guard

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.ui.R

/**
 * Aktionen, vor denen die App den Fingerabdruck (oder die Geräte-PIN)
 * verlangen kann (Entscheidung des Inhabers 21.11.2026). Welche es sind,
 * stellt man in den App-Einstellungen ein; [default] ist die Vorbelegung.
 *
 * Konsolen stehen bewusst nicht in dieser Liste und sind auch nicht wählbar:
 * Konsolen öffnen mit der bestehenden Anmeldung ohne zweite Abfrage
 * (docs/rules/ACCOUNTS-ACCESS.md, Inhaber 26.10.2026).
 */
enum class GuardAction(val id: String, val default: Boolean, @StringRes val label: Int, @StringRes val hint: Int) {
    AppOpen("app_open", false, R.string.guard_app_open, R.string.guard_app_open_hint),
    VolumeDelete("volume_delete", true, R.string.guard_volume_delete, R.string.guard_volume_delete_hint),
    ShareDelete("share_delete", true, R.string.guard_share_delete, R.string.guard_share_delete_hint),
    Users("users", true, R.string.guard_users, R.string.guard_users_hint),
    AccountSecurity("account_security", true, R.string.guard_account_security, R.string.guard_account_security_hint),
    Network("network", true, R.string.guard_network, R.string.guard_network_hint),
    Access("access", true, R.string.guard_access, R.string.guard_access_hint),
    Updates("updates", true, R.string.guard_updates, R.string.guard_updates_hint),
    Power("power", true, R.string.guard_power, R.string.guard_power_hint),
    Backups("backups", true, R.string.guard_backups, R.string.guard_backups_hint),
    Cluster("cluster", true, R.string.guard_cluster, R.string.guard_cluster_hint),
    ComputeDelete("compute_delete", true, R.string.guard_compute_delete, R.string.guard_compute_delete_hint),
    Plugins("plugins", false, R.string.guard_plugins, R.string.guard_plugins_hint),
    Settings("settings", false, R.string.guard_settings, R.string.guard_settings_hint),
}

/**
 * Fingerabdruck vor einer Aktion: `Guard.require(context, GuardAction.Power) { … }`.
 * Ist die Aktion in der Liste nicht gewählt, läuft [block] sofort. Sonst fragt
 * BiometricPrompt (starke Biometrie oder Geräte-PIN/Muster/Passwort als
 * Ausweich); nur nach Erfolg läuft [block]. Hat das Handy gar keine
 * Displaysperre, läuft die Aktion ohne Abfrage (die Einstellungsseite sagt das).
 * Nach einer Bestätigung gilt sie [graceSeconds] lang auch für weitere Aktionen.
 */
object Guard {
    private const val PREFS = "titan-guard"
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Wählbare Zeiten, wie lange eine Bestätigung gilt (Sekunden; 0 = jedes Mal fragen). */
    val GRACE_CHOICES = listOf(0, 30, 60, 300, 900)

    @Volatile
    private var lastOk = 0L

    /** Für Tests: ersetzt die Abfrage (true = bestätigt). */
    @Volatile
    var prompt: ((Context, GuardAction, (Boolean) -> Unit) -> Unit)? = null

    fun enabled(context: Context, action: GuardAction): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(action.id, action.default)

    fun set(context: Context, action: GuardAction, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(action.id, on).apply()
    }

    fun graceSeconds(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("grace", 60)

    fun setGraceSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("grace", seconds).apply()
    }

    /** Alles auf die Vorbelegung zurück. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        lastOk = 0L
    }

    /** Ob das Handy überhaupt eine Displaysperre oder Biometrie hat, mit der es fragen kann. */
    fun available(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val bio = context.getSystemService(BiometricManager::class.java)
        return keyguard?.isDeviceSecure == true || bio?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** [force]: auch wenn die Aktion nicht abgehakt ist und ohne Schonzeit (von Hand gesperrt). */
    fun require(context: Context, action: GuardAction, onDenied: () -> Unit = {}, force: Boolean = false, block: () -> Unit) {
        if (!force && !enabled(context, action)) return block()
        val grace = graceSeconds(context) * 1000L
        if (!force && grace > 0 && lastOk != 0L && SystemClock.elapsedRealtime() - lastOk < grace) return block()
        val done: (Boolean) -> Unit = { ok ->
            if (ok) {
                lastOk = SystemClock.elapsedRealtime()
                block()
            } else {
                onDenied()
            }
        }
        prompt?.let { return it(context, action, done) }
        if (!available(context)) return block()
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.guard_prompt_title))
            .setSubtitle(context.getString(action.label))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
            },
        )
    }
}

/** In Compose: `val guard = rememberGuard(); guard(GuardAction.Power) { … }`. */
@Composable
fun rememberGuard(): (GuardAction, () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) { { action, block -> Guard.require(context, action, block = block) } }
}
