package io.github.ra5on.titanos.vpn

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager

/** Welches Netz das Handy gerade hat; die SSID nur mit Standort. */
object NetworkWatch {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun locationGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun backgroundLocationGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    @Suppress("DEPRECATION") // getConnectionInfo: die einzige Stelle, die die SSID ohne Rückruf nennt (mit Standort).
    fun currentSsid(context: Context): String? {
        if (!locationGranted(context)) return null
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return cleanSsid(wifi.connectionInfo?.ssid)
    }

    /** Netz jetzt; [ourTunnelUp] trennt das eigene VPN von einem fremden. */
    @Suppress("DEPRECATION") // allNetworks: alle Netze, nicht nur das voreingestellte (das ist mit Tunnel das VPN).
    fun snapshot(context: Context, ourTunnelUp: Boolean): NetState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetState.NONE
        var connected = false
        var wifi = false
        var vpn = false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = true
                continue
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            connected = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
        }
        return NetState(connected = connected, onWifi = wifi, ssid = if (wifi) currentSsid(context) else null, otherVpn = vpn && !ourTunnelUp)
    }

    private fun request(): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, NetworkReceiver::class.java),
        // Das System hängt das Netz an: veränderbar.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Beobachtet Netzwechsel: mit einem PendingIntent auch bei geschlossener App
     * (Android startet dann den Empfänger), dazu im laufenden Prozess sofort.
     */
    fun watch(context: Context, onChange: () -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerNetworkCallback(request(), pending(context)) }
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
        }
        runCatching { cm.registerNetworkCallback(request(), cb) }.onSuccess { callback = cb }
    }

    fun unwatch(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(pending(context)) }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}

/** Android meldet einen Netzwechsel bei geschlossener App. */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VpnRuntime.init(context)
        VpnRuntime.evaluate("network")
    }
}

/** Nach dem Neustart des Handys oder einem Update der App. */
class VpnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        VpnRuntime.init(context)
        VpnRuntime.evaluate("boot")
    }
}
