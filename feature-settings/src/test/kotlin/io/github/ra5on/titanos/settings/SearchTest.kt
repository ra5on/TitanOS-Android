package io.github.ra5on.titanos.settings

import io.github.ra5on.titanos.settings.shell.SettingsIndex
import io.github.ra5on.titanos.settings.shell.SettingsSearch
import io.github.ra5on.titanos.ui.i18n.Tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Die Suche über jede Einstellung findet dasselbe wie im Web (settings-find.ts). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchTest {
    private val index = SettingsIndex.parse(File("src/main/assets/settings-index.json").readText())
    private val de = Tr.parse("de", File("src/main/assets/settings-i18n/de.json").readText())

    @Test
    fun findsPagesAndRows() {
        val hits = SettingsSearch.find(index, "zeitzone", admin = true, tr = de)
        assertEquals("/settings/timezone", hits.first().page)
        val ssh = SettingsSearch.find(index, "ssh", admin = true, tr = de)
        assertTrue(ssh.any { it.page == "/settings/access" })
    }

    @Test
    fun synonymsWork() {
        // „Glocke“ steht in keinem Titel, nur bei den Synonymen.
        assertTrue(SettingsSearch.find(index, "glocke", admin = true, tr = de).isNotEmpty())
    }

    @Test
    fun usersSeeOnlyTheirPages() {
        val hits = SettingsSearch.find(index, "firewall", admin = false, tr = de)
        assertTrue(hits.none { it.page == "/settings/firewall" })
        assertTrue(index.pages(false).all { !it.admin })
    }
}
