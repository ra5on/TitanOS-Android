package io.github.ra5on.titanos.plugins

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID

/** Abläufe über die Oberfläche: was die App beim NAS anfragt, wenn man klickt. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class PluginsFlowTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().pluginFixtures().start()
    private val shots = Shots(compose)

    @After fun end() = nas.close()

    private fun text(key: String) = io.github.ra5on.titanos.admin.adminTextForTest(key)

    private fun show(start: String? = null, role: String = "owner", setup: PluginsState.() -> Unit = {}) {
        val env = nas.env(role)
        shots.show(false) { PluginsProgram(env, start, {}, pollMs = 0, state = PluginsState(PluginsApi(env.http)).apply(setup)) }
    }

    private fun click(label: String, last: Boolean = false) {
        val nodes = compose.onAllNodes(io.github.ra5on.titanos.admin.testing.hasPlainText(label) and hasClickAction())
        (if (last) nodes.onLast() else nodes.onFirst()).performScrollTo().performClick()
        shots.settle()
    }

    private fun sent(method: String, path: String) = nas.seen.lastOrNull { it.method == method && it.path == path }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun installOfficialAfterRightsNotice() {
        show { picked = "wangxun-nic" }
        shots.untilText(text("plugins.downloadInstall"))
        click(text("plugins.downloadInstall"))
        shots.untilText(text("plugins.trustOfficial"))
        click(text("plugins.allow"))
        shots.until { sent("POST", "plugins") != null }
        assertEquals(json("""{"name":"wangxun-nic","volume":"Data","activate":true}"""), json(sent("POST", "plugins")!!.body))
    }

    @Test fun installCommunityWarnsAndSendsNameItself() {
        show { picked = "community-fan" }
        shots.untilText(text("plugins.downloadInstall"))
        click(text("plugins.downloadInstall"))
        shots.untilText(text("plugins.trustCommunity"))
        click(text("plugins.activateNow"))
        click(text("plugins.allow"))
        shots.until { sent("POST", "plugins/external") != null }
        assertEquals(
            json("""{"source":"s1","name":"community-fan","volume":"Data","confirm":"community-fan","activate":false}"""),
            json(sent("POST", "plugins/external")!!.body),
        )
    }

    @Test fun updateSendsNameAsConfirmation() {
        show { picked = "werkzeug" }
        shots.untilText(text("plugins.update"))
        click(text("plugins.update"))
        shots.untilText(text("plugins.updateText"))
        click(text("plugins.update"), last = true)
        shots.until { sent("POST", "plugins/werkzeug/update") != null }
        assertEquals(json("""{"path":"/mnt/Data/media/werkzeug.plugin.json","confirm":"werkzeug"}"""), json(sent("POST", "plugins/werkzeug/update")!!.body))
    }

    @Test fun removeAfterPlainDialog() {
        show { picked = "intel-gpu-tools" }
        shots.untilText(text("plugins.remove"))
        click(text("plugins.remove"))
        click(text("plugins.removeSubmit"))
        shots.until { sent("DELETE", "plugins/intel-gpu-tools") != null }
    }

    @Test fun switchOff() {
        show { picked = "intel-gpu-tools" }
        shots.untilText(text("plugins.enabled"))
        click(text("plugins.enabled"))
        shots.until { sent("POST", "plugins/intel-gpu-tools/disable") != null }
    }

    @Test fun dismissRecommendation() {
        show()
        shots.untilText(text("plugins.recommendedDismiss"))
        click(text("plugins.recommendedDismiss"))
        shots.until { sent("POST", "plugins/recommendations/wangxun-nic/dismiss") != null }
    }

    @Test fun netbirdKeyLeavesOnceAndFieldIsCleared() {
        val key = UUID.randomUUID().toString()
        show { picked = "netbird" }
        shots.untilText(text("netbird.setupKey"))
        compose.onAllNodes(hasSetTextAction() and hasContentDescription(text("netbird.setupKey"))).onFirst().performScrollTo().performTextInput(key)
        shots.settle()
        click(text("netbird.saveKey"))
        shots.until { sent("PUT", "plugins/netbird/setup-key") != null }
        assertEquals(json("""{"setupKey":"$key"}"""), json(sent("PUT", "plugins/netbird/setup-key")!!.body))
        assertEquals(0, compose.onAllNodes(hasText(key, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains(key) == true })
    }

    @Test fun nvidiaChooseVersion() {
        show { picked = "nvidia" }
        shots.untilText(text("plugins.choice.version"))
        click(text("plugins.choice.version"))
        click("620.12.03")
        click(text("plugins.choice.buildUse"))
        shots.until { sent("POST", "plugins/nvidia/updates") != null }
        assertEquals(json("""{"action":"choose","version":"620.12.03"}"""), json(sent("POST", "plugins/nvidia/updates")!!.body))
    }

    @Test fun linkInspectThenInstall() {
        show { linking = true; volume = "Data" }
        shots.untilText(text("plugins.fromLinkURL"))
        compose.onAllNodes(hasSetTextAction() and hasContentDescription(text("plugins.fromLinkURL"))).onFirst().performTextInput("https://example.org/linkplugin.plugin.json")
        shots.settle()
        click(text("plugins.fromLinkCheck"))
        shots.untilText("Link-Plugin")
        click(text("plugins.downloadInstall"), last = true)
        shots.until { sent("POST", "plugins/external") != null }
        assertEquals(
            json("""{"url":"https://example.org/linkplugin.plugin.json","volume":"Data","confirm":"linkplugin","activate":true}"""),
            json(sent("POST", "plugins/external")!!.body),
        )
    }

    @Test fun addSource() {
        show { sourcesOpen = true }
        shots.untilText(text("plugins.sourceURL"))
        compose.onAllNodes(hasSetTextAction() and hasContentDescription(text("plugins.sourceURL"))).onFirst().performTextInput("https://example.org/plugins.json")
        shots.settle()
        click(text("plugins.sourceAdd"))
        shots.until { sent("POST", "plugins/sources") != null && sent("POST", "plugins/catalog") != null }
        assertEquals(json("""{"url":"https://example.org/plugins.json"}"""), json(sent("POST", "plugins/sources")!!.body))
    }

    @Test fun cleanupOnlyUnprotected() {
        show("cleanup")
        shots.untilText("0.55.0")
        click("0.55.0 · " + text("cleanup.select"))
        click(text("cleanup.preview"))
        click(text("cleanup.checked"))
        click(text("netx.remove"))
        shots.until { sent("POST", "plugins/cleanup") != null }
        assertEquals(json("""{"selection":[{"version":"0.55.0","fingerprint":"old"}]}"""), json(sent("POST", "plugins/cleanup")!!.body))
    }

    @Test fun userSeesNothing() {
        show(role = "user")
        shots.untilText(text("error.forbidden"))
        assertEquals(emptyList<Any>(), nas.seen.toList())
    }
}
