package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Was die Seiten „Hardware und Energie“ an das NAS schicken: Methode, Pfad, Inhalt (unbekannte Felder bleiben), Fingerabdruck. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class HardwareApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)
    private val guards = mutableListOf<GuardAction>()

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        HardwareFixtures.install(nas)
        nas.start()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Guard.reset(context)
        Guard.setGraceSeconds(context, 0)
        for (a in GuardAction.entries) Guard.set(context, a, true)
        Guard.prompt = { _, action, done -> guards += action; done(true) }
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
    }

    private fun page(id: String, wait: String) {
        val nav = SettingsNav()
        compose.setContent { TitanTheme(dark = false) { SettingsApp(nas.env(), onClose = {}, nav = nav) } }
        shots.settle(300)
        nav.openPage(id)
        shots.until { nas.requests.any { it.second.substringBefore('?') == wait } }
        shots.settle(500)
    }

    private fun pick(label: String, option: String) {
        compose.onAllNodes(hasContentDescription(label)).onFirst().performScrollTo().performClick()
        shots.settle(300)
        compose.onAllNodes(hasText(option, substring = true)).onLast().performClick()
    }

    private fun sent(method: String, path: String): JsonObject {
        shots.until { nas.requests.any { it.first == method && it.second == path } }
        val body = nas.requests.last { it.first == method && it.second == path }.third
        return if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body) as JsonObject
    }

    @Test
    fun powerButtonKeepsUnknownFields() {
        page("power-button", "system/power-button")
        pick("Bei kurzem Tastendruck", "Neu starten")
        val body = sent("PUT", "system/power-button")
        assertEquals("reboot", body.str("action"))
        assertEquals(2, body.int("later"))
        assertEquals(listOf(GuardAction.Settings), guards)
    }

    @Test
    fun memoryBudgetKeepsUnknownFields() {
        page("memory", "system/memory")
        pick("Schreibpufferbudget für Dateiübertragungen", "50 %")
        val body = sent("PUT", "system/memory")
        assertEquals(50, body.int("cachePercent"))
        assertEquals(1, body.int("later"))
        assertTrue(GuardAction.Settings in guards)
    }

    @Test
    fun energyProfileKeepsTheOtherSettings() {
        page("energy", "system/energy")
        pick("CPU-Taktsteuerung", "performance")
        val body = sent("PUT", "system/energy")
        assertEquals("performance", body.str("governor"))
        assertEquals("x", body.str("future"))
    }
}
