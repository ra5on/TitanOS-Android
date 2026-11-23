package io.github.ra5on.titanos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.home.ALL_PROGRAMS
import io.github.ra5on.titanos.home.programLabelRes
import io.github.ra5on.titanos.settings.shell.SettingsIndex
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.i18n.Tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** „Schnell finden“ wie die Befehlspalette im Web (Palette.kt): jeder Eintrag hat einen Namen und ein Ziel in der App. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de", sdk = [35])
class PaletteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tr = Tr.parse("de", java.io.File("../feature-settings/src/main/assets/settings-i18n/de.json").readText())
    private val index = SettingsIndex.parse(java.io.File("../feature-settings/src/main/assets/settings-index.json").readText())
    private val web: (String) -> String = paletteText(context, tr)

    private fun all(admin: Boolean) = palettePages(
        admin, web, index, tr,
        ALL_PROGRAMS.map { it.id to context.getString(programLabelRes(it.id)) },
        APP_PAGES.map { it to it },
    )

    @Test
    fun everyEntryHasANameAndATargetInTheApp() {
        for (e in all(admin = true)) {
            assertTrue("Ohne Namen: $e", e.label.isNotBlank() && !Regex("^[a-z]+(\\.[a-zA-Z]+)+$").matches(e.label))
            if (e.href == "/" || e.href.startsWith("app:")) continue
            val target = linkTarget(e.href)
            assertTrue("Kein Ziel in der App: ${e.href} → $target", target is LinkTarget.Program)
        }
    }

    @Test
    fun pagesByRoleLikeTheWeb() {
        val user = palettePages(false, web, index, tr, listOf("mail" to "Mail"), emptyList()).map { it.href }
        assertTrue("$user", "/files" in user && "/account" in user && "/mail" in user)
        assertTrue("$user", "/storage" !in user && "/settings/network" !in user)
        val admin = all(admin = true).map { it.href }
        assertTrue("$admin", "/storage" in admin && "/settings/network" in admin && "/nvr/diagnosis" in admin)
        // Jede Adresse nur einmal (Programm und Seite „Docker“ sind dasselbe).
        assertEquals(admin.groupBy { it }.filter { it.value.size > 1 }.keys.toString(), admin.size, admin.toSet().size)
    }

    @Test
    fun singleSettingsCarryTabAndPlace() {
        val hits = paletteSettings("ssh", admin = true, settings = tr, index = index)
        assertTrue(hits.isNotEmpty())
        for (h in hits) assertTrue(h.href, linkTarget(h.href) is LinkTarget.Program)
        // Die Adresse öffnet in der Systemsteuerung Seite, Reiter und Fundstelle wie ihre eigene Suche.
        val withFind = hits.first { it.href.contains("#find=") && it.href.startsWith("/settings/") }
        val nav = SettingsNav(withFind.href)
        assertNotEquals(null, nav.page)
        assertTrue(nav.find != null)
        assertTrue(paletteSettings("  ", admin = true, settings = tr, index = index).isEmpty())
    }

    @Test
    fun searchFoldsAccentsAndNeedsEveryWord() {
        val entries = all(admin = true)
        assertTrue(findPaletteEntries(entries, "aktivitat").toString(), findPaletteEntries(entries, "aktivitat").any { it.href == "/settings/systemdata" })
        assertTrue(findPaletteEntries(entries, "vpn").toString(), findPaletteEntries(entries, "VPN").any { it.href == "app:vpn" })
        assertTrue(findPaletteEntries(entries, "kalender").toString(), findPaletteEntries(entries, "kalender").any { it.href == "/calendar" })
        assertTrue(findPaletteEntries(entries, "zzqx").isEmpty())
    }

    @Test
    fun recentFirstWithoutQuery() {
        val all = listOf(PaletteEntry("/a", "A", "S"), PaletteEntry("/b", "B", "S"))
        val shown = paletteEntries(all, "", listOf("/b"), "Zuletzt", emptyList())
        assertEquals(listOf("/b", "/a"), shown.map { it.href })
        assertEquals("Zuletzt", shown.first().section)
        assertEquals(listOf("/a"), paletteEntries(all, "a", listOf("/b"), "Zuletzt", emptyList()).map { it.href })
    }

    @Test
    fun vmsAndLxcOpenTheirEntry() {
        assertEquals("pihole", queryValue("/vms?vm=pihole", "vm"))
        assertEquals(LinkTarget.Program("vms", "/vms?vm=pihole"), linkTarget("/vms?vm=pihole"))
        assertEquals(LinkTarget.Program("lxc", "/lxc?ct=web"), linkTarget("/lxc?ct=web"))
        assertEquals(LinkTarget.Program("docker", "/docker/apps/immich"), linkTarget("/docker/apps/immich"))
    }
}
