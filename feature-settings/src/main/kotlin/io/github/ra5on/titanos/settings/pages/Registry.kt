package io.github.ra5on.titanos.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import io.github.ra5on.titanos.settings.shell.SettingsNav

/** Was eine Seite von der Systemsteuerung bekommt: Reiter, Ziel der Suche, andere Seiten öffnen. */
@Stable
class PageArgs(val nav: SettingsNav) {
    /** Der gewählte Reiter (`?tab=`), null: der erste. */
    var tab: String?
        get() = nav.tab
        set(v) { nav.tab = v }

    /** Öffnet eine Seite oder Unterseite der Systemsteuerung (`ups`, `backups/data`). */
    fun openPage(id: String) = nav.openPage(id)
}

/** Alle Seiten der Systemsteuerung unter ihrer Kennung aus der Adresse. */
object Pages {
    @Composable
    fun Show(id: String, a: PageArgs) {
        when (id) {
            "about" -> io.github.ra5on.titanos.settings.pages.system.AboutPage(a)
            "updates" -> io.github.ra5on.titanos.settings.pages.system.UpdatesPage(a)
            "appearance", "appearance/dock" -> io.github.ra5on.titanos.settings.pages.system.AppearancePage(a)
            "timezone" -> io.github.ra5on.titanos.settings.pages.system.TimezonePage(a)
            "services" -> io.github.ra5on.titanos.settings.pages.system.ServicesPage(a)
            "viewer" -> io.github.ra5on.titanos.settings.pages.system.ViewerPage(a)
            "programs" -> io.github.ra5on.titanos.settings.pages.system.ProgramsPage(a)
            "stick" -> io.github.ra5on.titanos.settings.pages.system.StickPage(a)
            "hardware" -> io.github.ra5on.titanos.settings.pages.hardware.HardwarePage(a)
            "temperatures" -> io.github.ra5on.titanos.settings.pages.hardware.TemperaturesPage(a)
            "energy" -> io.github.ra5on.titanos.settings.pages.hardware.EnergyPage(a)
            "power-button" -> io.github.ra5on.titanos.settings.pages.hardware.PowerButtonPage(a)
            "ups" -> io.github.ra5on.titanos.settings.pages.hardware.UpsPage(a)
            "memory" -> io.github.ra5on.titanos.settings.pages.hardware.MemoryPage(a)
            "systemdata" -> io.github.ra5on.titanos.settings.pages.hardware.SystemDataPage(a)
            "files" -> io.github.ra5on.titanos.settings.pages.hardware.FilesPage(a)
            "network" -> io.github.ra5on.titanos.settings.pages.network.NetworkPage(a)
            "wireguard" -> io.github.ra5on.titanos.settings.pages.network.WireGuardPage(a)
            "ddns" -> io.github.ra5on.titanos.settings.pages.network.DdnsPage(a)
            "ipv64" -> io.github.ra5on.titanos.settings.pages.network.Ipv64Page(a)
            "letsencrypt" -> io.github.ra5on.titanos.settings.pages.network.LetsEncryptPage(a)
            "proxy" -> io.github.ra5on.titanos.settings.pages.network.ProxyPage(a)
            "firewall" -> io.github.ra5on.titanos.settings.pages.network.FirewallPage(a)
            "users" -> io.github.ra5on.titanos.settings.pages.security.UsersPage(a)
            "access" -> io.github.ra5on.titanos.settings.pages.security.AccessPage(a)
            "api" -> io.github.ra5on.titanos.settings.pages.security.ApiPage(a)
            "notifications" -> io.github.ra5on.titanos.settings.pages.security.NotificationsPage(a)
            "cluster" -> io.github.ra5on.titanos.settings.pages.security.ClusterPage(a)
            "mcp" -> io.github.ra5on.titanos.settings.pages.security.McpPage(a)
            "backups", "backups/data", "backups/applications", "backups/configuration", "backups/coverage", "backups/restore" ->
                io.github.ra5on.titanos.settings.pages.backups.BackupsPage(a, id)
            "homeassistant" -> io.github.ra5on.titanos.settings.pages.smarthome.HomeAssistantPage(a)
            "agent" -> io.github.ra5on.titanos.settings.pages.smarthome.AgentPage(a)
            "speech" -> io.github.ra5on.titanos.settings.pages.smarthome.SpeechPage(a)
            "app/look" -> io.github.ra5on.titanos.settings.pages.app.DeviceLookPage()
            "app/guard" -> io.github.ra5on.titanos.settings.pages.app.GuardPage()
            else -> Unknown(id)
        }
    }

    /** „Alle Einstellungen“ am großen Bildschirm: Übersicht (SettingsOverview.svelte). */
    @Composable
    fun Overview(a: PageArgs, wide: Boolean) = io.github.ra5on.titanos.settings.pages.system.OverviewPage(a, wide)
}

@Composable
private fun Unknown(id: String) {
    io.github.ra5on.titanos.ui.components.Banner(io.github.ra5on.titanos.ui.i18n.t("error.not_found") + " ($id)", tone = io.github.ra5on.titanos.ui.components.Tone.Warn)
}
