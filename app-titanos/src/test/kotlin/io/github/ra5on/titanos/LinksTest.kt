package io.github.ra5on.titanos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Links aus Systemsteuerung, Konto, Ablage und Werkzeuge führen in der App ins richtige Programm (Links.kt). */
class LinksTest {
    private fun program(path: String) = linkTarget(path) as LinkTarget.Program

    @Test
    fun everyLinkOfTheSettingsHasATarget() {
        // Alle festen Ziele, die feature-settings und feature-account öffnen (grep `env.open("/`).
        val seen = mapOf(
            "/users" to "users", "/users/new" to "users", "/storage" to "storage", "/storage/smart?disk=sda" to "storage",
            "/plugins" to "plugins", "/tools/activity" to "tools", "/tools/tasks" to "tools", "/docker" to "docker",
            "/docker/apps/immich#h-proxy" to "docker", "/nvr/rights" to "nvr", "/account" to "account", "/account#webdav" to "account",
            "/settings/speech" to "settings", "/settings/firewall?tab=policies" to "settings",
            "/files?root=volume%2Fdata&path=backup" to "files", "/mail" to "mail", "/calendar" to "calendar", "/contacts" to "contacts",
        )
        for ((path, id) in seen) assertEquals(path, id, program(path).id)
    }

    @Test
    fun webAddressesWithOtherNamesInTheApp() {
        // Durchspielen verweist im Web auf /backups (dort ins Leere): gemeint sind die Sicherungen.
        assertEquals(LinkTarget.Program("settings", "/settings/backups"), linkTarget("/backups"))
        assertEquals(LinkTarget.Program("tools", "/tools/logs?unit=x"), linkTarget("/logs?unit=x"))
        assertEquals(LinkTarget.Program("docker", "/docker/catalog"), linkTarget("/apps"))
    }

    @Test
    fun outsideTheNasAndOnlyOnTheWeb() {
        assertEquals(LinkTarget.External("https://example.org/a"), linkTarget("https://example.org/a"))
        // Der Agent: ohne eigenes Programm die Agent-App; sobald die TitanOS-App ihn selbst zeigt
        // („agent“ in PROGRAMS, Arbeitsplatz von F1), das Programm in der App. Beides ist richtig.
        val agent = linkTarget("/agent?profile=assist")
        assertTrue("$agent", agent == LinkTarget.Agent("/agent?profile=assist") || agent == LinkTarget.Program("agent", "/agent?profile=assist"))
        assertEquals(LinkTarget.WebOnly, linkTarget("/welcome?step=4"))
        assertEquals(LinkTarget.WebOnly, linkTarget("javascript:alert(1)"))
    }

    @Test
    fun startPagesInsideAProgram() {
        assertEquals("activity", startPage("/tools/activity", "tools"))
        assertEquals("smart?disk=sda", startPage("/storage/smart?disk=sda", "storage"))
        assertEquals("rights", startPage("/nvr/rights", "nvr"))
        assertEquals("apps/immich", startPage("/docker/apps/immich#h-proxy", "docker"))
        assertEquals("new", startPage("/users/new", "users"))
        assertNull(startPage("/storage", "storage"))
        assertNull(startPage("/files?root=x", "files"))
        assertNull(startPage(null, "tools"))
        assertEquals("volume/data", queryValue("/files?root=volume%2Fdata&path=a%20b", "root"))
        assertEquals("a b", queryValue("/files?root=volume%2Fdata&path=a%20b", "path"))
        assertNull(queryValue("/files", "root"))
    }

    @Test
    fun openFilesFromTheSharesGoesThroughTheAddress() {
        // „In Dateien öffnen“ (Freigaben): die Stelle steht in der Adresse, LinkedProgram öffnet sie.
        val link = filesLink("share/Urlaub & Co", "2026/Strand+See")
        assertEquals(LinkTarget.Program("files", link), linkTarget(link))
        assertEquals("share/Urlaub & Co", queryValue(link, "root"))
        assertEquals("2026/Strand+See", queryValue(link, "path"))
        assertNull(queryValue(filesLink("share/Docs", ""), "path"))
    }
}
