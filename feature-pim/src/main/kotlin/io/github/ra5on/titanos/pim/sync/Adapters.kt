package io.github.ra5on.titanos.pim.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.ContactsContract
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.CertificateChangedException
import java.io.IOException

/**
 * Das Konto hat kein Passwort und keinen Token: Anlegen geht nur aus der App
 * (nach dem Koppeln). „Konto hinzufügen“ in den Android-Einstellungen öffnet
 * deshalb die App.
 */
class Authenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?): Bundle {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return Bundle().apply { if (open != null) putParcelable(AccountManager.KEY_INTENT, open) }
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle =
        Bundle().apply { putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION) }
    override fun getAuthTokenLabel(authTokenType: String?): String? = null
    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/** Gemeinsamer SyncAdapter für Kontakte und Kalender. */
class PimSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
        PimSync.perform(context, account, extras, authority, provider, result)
    }
}

class ContactsSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = adapter(this).syncAdapterBinder

    companion object {
        private var instance: PimSyncAdapter? = null
        @Synchronized
        fun adapter(c: Context) = instance ?: PimSyncAdapter(c.applicationContext).also { instance = it }
    }
}

class CalendarSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = adapter(this).syncAdapterBinder

    companion object {
        private var instance: PimSyncAdapter? = null
        @Synchronized
        fun adapter(c: Context) = instance ?: PimSyncAdapter(c.applicationContext).also { instance = it }
    }
}

/** Gründe, warum ein Abgleich nicht lief (stehen in SyncStatus.problem). */
object Problems {
    const val NOT_PAIRED = "not_paired"
    const val NETWORK = "network"
    const val ROAMING = "roaming"
    const val METERED = "metered"
    const val CHARGING = "charging"
    const val UNREACHABLE = "unreachable"
    const val CERTIFICATE = "certificate"
    const val NEEDS_119 = "needs_119"
    const val UNAVAILABLE = "unavailable"
    const val PERMISSION = "permission"
    const val ERROR = "error"
}

object PimSync {
    val AUTHORITIES = listOf(ContactsContract.AUTHORITY, CalendarContract.AUTHORITY)

    /** Richtet Abgleich und Zeitplan nach den Einstellungen ein (nach dem Koppeln und bei jeder Änderung). */
    fun configure(context: Context) {
        val account = PimAccount.current(context) ?: return
        val p = SyncStore(context).load()
        for (authority in AUTHORITIES) {
            val on = if (authority == ContactsContract.AUTHORITY) p.contacts else p.calendars
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.setSyncAutomatically(account, authority, on && p.uploadOnChange || on && p.intervalMinutes > 0)
            ContentResolver.getPeriodicSyncs(account, authority).forEach { ContentResolver.removePeriodicSync(account, authority, it.extras) }
            if (on && p.intervalMinutes > 0) {
                ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, maxOf(15, p.intervalMinutes) * 60L)
            }
        }
    }

    /** „Jetzt abgleichen“ bzw. eine Änderung am NAS: sofort, auch außerhalb des Zeitplans. */
    fun requestNow(context: Context, manual: Boolean = true, only: String? = null) {
        val account = PimAccount.current(context) ?: return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, manual)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        for (a in AUTHORITIES) if (only == null || only == a) ContentResolver.requestSync(account, a, extras)
    }

    fun running(context: Context): Boolean {
        val account = PimAccount.current(context) ?: return false
        return AUTHORITIES.any { ContentResolver.isSyncActive(account, it) }
    }

    /** Warum jetzt nicht abgeglichen werden darf; null = darf. */
    fun blocked(context: Context, p: SyncPrefs): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return Problems.NETWORK
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Problems.NETWORK
        if (p.network == NetworkRule.Unmetered && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return Problems.METERED
        if (p.network != NetworkRule.Any && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) return Problems.ROAMING
        if (p.chargingOnly) {
            val bm = context.getSystemService(BatteryManager::class.java)
            if (bm?.isCharging != true) return Problems.CHARGING
        }
        return null
    }

    internal fun perform(context: Context, account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
        val store = SyncStore(context)
        val p = store.load()
        val manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL)
        val kind = if (authority == ContactsContract.AUTHORITY) "contacts" else "calendar"
        val now = System.currentTimeMillis()
        fun problem(code: String?) = store.updateStatus { it.copy(lastRun = now, problem = code) }
        // „Jetzt abgleichen“ gilt auch außerhalb der Netzregel, nur nicht ohne Netz.
        blocked(context, p)?.let { code ->
            if (!manual || code == Problems.NETWORK) { problem(code); return }
        }
        val nas = PimRuntime.nas() ?: run { problem(Problems.NOT_PAIRED); return }
        val api = PimSyncApi(nas)
        val counts = SyncCounts()
        try {
            val states = if (kind == "contacts") ContactsSync(account, api, provider, p).run(counts) else CalendarSync(account, api, provider, p).run(counts)
            result.stats.numInserts += counts.down.toLong()
            result.stats.numDeletes += counts.removed.toLong()
            result.stats.numUpdates += counts.up.toLong()
            store.updateStatus { s ->
                s.copy(
                    collections = s.collections.filter { it.kind != kind } + states,
                    lastRun = now, lastOk = System.currentTimeMillis(), problem = null,
                    conflicts = s.conflicts + counts.conflicts, uploaded = counts.up, downloaded = counts.down,
                )
            }
        } catch (e: SyncNotSupportedException) {
            problem(Problems.NEEDS_119)
        } catch (e: CertificateChangedException) {
            problem(Problems.CERTIFICATE); result.stats.numAuthExceptions++
        } catch (e: SecurityException) {
            problem(Problems.PERMISSION)
        } catch (e: ApiException) {
            problem(if (e.status == 503) Problems.UNAVAILABLE else Problems.ERROR)
            if (e.status == 503) result.stats.numIoExceptions++ else result.databaseError = true
        } catch (e: IOException) {
            val cert = generateSequence(e as Throwable) { it.cause }.any { it is CertificateChangedException }
            problem(if (cert) Problems.CERTIFICATE else Problems.UNREACHABLE); result.stats.numIoExceptions++
        } catch (e: Exception) {
            android.util.Log.w("TitanSync", "sync failed", e)
            problem(Problems.ERROR); result.databaseError = true
        }
    }
}
