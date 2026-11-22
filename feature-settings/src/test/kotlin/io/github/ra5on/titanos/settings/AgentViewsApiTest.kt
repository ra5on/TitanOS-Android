package io.github.ra5on.titanos.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.smarthome.BuddyViewSheet
import io.github.ra5on.titanos.settings.pages.smarthome.CatalogViewSheet
import io.github.ra5on.titanos.settings.pages.smarthome.EmergencyPanel
import io.github.ra5on.titanos.settings.pages.smarthome.GuardPanel
import io.github.ra5on.titanos.settings.pages.smarthome.PaperViewSheet
import io.github.ra5on.titanos.ui.components.OverlayHost
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
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
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Was die Ansichten der Agent-Funktionen ans NAS schicken: Methode, Pfad, Inhalt, Fingerabdruck. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h1400dp-xhdpi", sdk = [36])
class AgentViewsApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)
    private val guards = mutableListOf<GuardAction>()

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        AgentViewFixtures.install(nas)
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

    private fun de(key: String) = SmartHomeFixtures.de(key)

    private fun host(wait: String, content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            val context = LocalContext.current
            val tr = remember { Tr.load(context, "settings-i18n", "de") }
            val env = remember { nas.env() }
            io.github.ra5on.titanos.ui.theme.TitanTheme(dark = false) {
                CompositionLocalProvider(LocalTr provides tr, LocalEnv provides env) {
                    OverlayHost { Box(Modifier.fillMaxSize()) { content() } }
                }
            }
        }
        compose.waitForIdle()
        shots.until { nas.requests.any { it.second.startsWith(wait) } }
        shots.settle(600)
    }

    private fun panel(wait: String, content: @Composable () -> Unit) =
        host(wait) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } }

    /** Klickt den letzten Knoten mit diesem Text (im Dialog liegt er oben auf). */
    private fun click(text: String) {
        val node = compose.onAllNodes(hasText(text)).onLast()
        runCatching { node.performScrollTo() }
        node.performClick()
        shots.settle(500)
    }

    private fun sent(method: String, path: String): JsonObject {
        shots.until { nas.requests.any { it.first == method && it.second == path } }
        val body = nas.requests.last { it.first == method && it.second == path }.third
        return if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body) as JsonObject
    }

    @Test
    fun guardRestoreAsksOnceAndNeedsTheFingerprint() {
        panel("agent/protect/guard") { GuardPanel() }
        click(de("agent.f.protect.g.restoreBtn"))
        click(de("agent.f.protect.g.confirmBtn.restore"))
        sent("POST", "agent/protect/guard/a1/restore")
        assertEquals(listOf(GuardAction.Backups), guards)
    }

    @Test
    fun guardFalseAlarmWithoutFingerprint() {
        panel("agent/protect/guard") { GuardPanel() }
        click(de("agent.f.protect.g.falseAlarm"))
        click(de("agent.f.protect.g.confirmBtn.dismiss"))
        sent("POST", "agent/protect/guard/a1/dismiss")
        assertTrue(guards.isEmpty())
    }

    @Test
    fun emergencyGrantNeedsTheFingerprint() {
        panel("agent/protect/emergency") { EmergencyPanel("alex", admin = true) }
        click(de("agent.f.protect.e.asks").replace("{name}", "kim"))
        click(de("agent.f.protect.e.grantNow"))
        click(de("agent.f.protect.e.grantNow"))
        sent("POST", "agent/protect/emergency/r1/grant")
        assertEquals(listOf(GuardAction.Users), guards)
    }

    @Test
    fun buddyEndWithData() {
        host("agent/buddy") { BuddyViewSheet {} }
        click(de("agent.f.family.buddy.end"))
        click(de("agent.f.family.buddy.endData").replace("{name}", "Eva"))
        click(de("agent.f.family.buddy.end"))
        sent("DELETE", "agent/buddy/partners/b1?data=1")
    }

    @Test
    fun buddyEditKeepsAnUnchangedOldQuota() {
        // Vor 1.03 gespeicherte Quoten sind ganze GiB: 500 GiB zeigt das Feld als 537 GB; unverändert gespeichert bleibt der alte Wert.
        nas.on("GET agent/buddy", AgentViewFixtures.buddy.replace("\"quota\":500000000000", "\"quota\":536870912000"))
        host("agent/buddy") { BuddyViewSheet {} }
        click(de("agent.f.family.buddy.change"))
        click(de("agent.f.family.save"))
        val body = sent("PUT", "agent/buddy/partners/b1")
        assertEquals(536870912000L, body.long("quota"))
    }

    @Test
    fun paperAssignSendsTheFields() {
        host("agent/paper") { PaperViewSheet {} }
        click(de("agent.f.docs.assign"))
        click(de("common.save"))
        val body = sent("POST", "agent/paper/p3/assign")
        assertEquals(setOf("sender", "type", "kind", "date"), body.keys)
        assertEquals("tax", body.str("type"))
    }

    @Test
    fun catalogInstallSendsKeyAndDigest() {
        host("agent/skills") { CatalogViewSheet {} }
        click("Rechnungen sortieren")
        click(de("agent.f.family.catalog.install"))
        val body = sent("POST", "agent/catalog/install")
        assertEquals("titanos/rechnungen", body.str("key"))
        assertEquals("d1", body.str("digest"))
    }
}
