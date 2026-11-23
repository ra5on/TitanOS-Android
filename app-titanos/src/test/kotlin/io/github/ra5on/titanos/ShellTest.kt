package io.github.ra5on.titanos

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.net.NasProfile
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.ui.components.DisclaimerStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die echte Activity, gekoppelt mit einem NAS, das nicht antwortet: Dock,
 * Seitenmenü mit App-Einstellungen, „Start“ schließt alles, und der
 * Haftungsausschluss für eine bestehende Installation (PLAN 117).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w412dp-h915dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ShellTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TitanApp get() = ApplicationProvider.getApplicationContext()
    private val id = "nshelltest1"

    @Before
    fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
        app.getSharedPreferences("titan-disclaimer", Context.MODE_PRIVATE).edit().clear().commit()
        // Ein NAS, das sofort ablehnt (Port 9 am eigenen Gerät): die Oberfläche steht trotzdem.
        val pairing = Pairing("zeus", "dshell", listOf(PinnedAddress("127.0.0.1:9", "pinShell")), "nas")
        app.graph.registry.add(NasProfile(id, pairing, label = "Zuhause"))
        // Wie nach dem Koppeln: die Modelle des gewählten NAS neu.
        app.graph.paired(pairing)
    }

    @After
    fun tearDown() {
        app.graph.forget(id, byNas = true)
        app.getSharedPreferences("titan-disclaimer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Test
    fun existingInstallConfirmsTheDisclaimerOnce() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithText("Ich habe den Haftungsausschluss gelesen und verstanden.").performClick()
            compose.onNodeWithText("Weiter").performClick()
            compose.waitForIdle()
            assertTrue(DisclaimerStore.accepted(app))
            compose.onNode(hasContentDescription("Start") and tab).fetchSemanticsNode()
        }
    }

    @Test
    fun dockAndSideMenu() {
        DisclaimerStore.accept(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            // Seitenmenü: Konto, NAS, App-Einstellungen; kein Cluster-Knopf mehr oben rechts.
            compose.onNodeWithContentDescription("Seitenmenü").performClick()
            compose.onNodeWithText("App-Einstellungen").performClick()
            compose.onNodeWithText("Startbildschirm und Dock").performClick()
            compose.onNodeWithText("Dock immer anzeigen").fetchSemanticsNode()
            // Das Dock bleibt in den Einstellungen sichtbar; „Start“ führt sofort zurück.
            compose.onNode(hasContentDescription("Start") and tab).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Seitenmenü").fetchSemanticsNode()
            assertTrue(compose.onAllNodes(hasText("Dock immer anzeigen")).fetchSemanticsNodes().isEmpty())
            // Mitteilungen über das Dock, dann wieder Start.
            compose.onNode(hasContentDescription("Mitteilungen") and tab).performClick()
            compose.waitForIdle()
            assertTrue(compose.onAllNodes(hasContentDescription("Seitenmenü")).fetchSemanticsNodes().isEmpty())
            compose.onNode(hasContentDescription("Start") and tab).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Seitenmenü").fetchSemanticsNode()
        }
    }
}
