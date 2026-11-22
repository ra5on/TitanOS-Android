package io.github.ra5on.titanos.net

/**
 * Was die Verbindung zum NAS vom eigenen VPN der App wissen muss (feature-vpn
 * setzt es beim Start der App): ob der Tunnel gerade läuft, und wem sie meldet,
 * dass das NAS direkt im Heimnetz geantwortet hat (daraus lernt die App die
 * Heim-WLANs). Ohne feature-vpn: kein Tunnel, keine Meldung.
 */
object VpnRoute {
    @Volatile
    var isUp: () -> Boolean = { false }

    @Volatile
    var onHome: (() -> Unit)? = null

    /**
     * Gehört das VPN zu dieser Kopplung? Mit mehreren NAS lernt nur das NAS
     * des VPN die Heim-WLANs; ein zweites NAS im WLAN eines Freundes darf
     * dessen Netz nicht zum „Heim“ machen. Ohne Angabe: ja (eine Kopplung).
     */
    @Volatile
    var owns: (Pairing) -> Boolean = { true }

    internal fun reachedAtHome() {
        try {
            onHome?.invoke()
        } catch (_: Exception) {
            // Nur ein Hinweis fürs Lernen der Heim-WLANs; die Anfrage zählt.
        }
    }
}
