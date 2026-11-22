package io.github.ra5on.titanos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri

/**
 * Woher die App kommt: GitHub (Standard, mit Selbst-Update) oder Google Play
 * (Bauvariante `play`, PLAN 116). Die Apps setzen `R.bool.titan_play` in
 * `src/play/res`; Bibliotheken fragen hier, statt eigene Varianten zu haben.
 */
object Distribution {
    fun play(context: Context): Boolean = context.resources.getBoolean(R.bool.titan_play)

    /**
     * Die Ausnahme vom Akku-Sparen. Von GitHub direkt erfragt (Mitteilungen
     * ohne Google, VPN). Google Play erlaubt die direkte Frage
     * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) nur, wenn Doze die
     * Kernfunktion bricht (developer.android.com „Optimize for Doze and App
     * Standby“, gelesen 22.11.2026); die Play-Variante öffnet deshalb die Liste
     * der Akku-Optimierung, in der man die App selbst auswählt.
     */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context): Intent =
        if (play(context)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        }
}
