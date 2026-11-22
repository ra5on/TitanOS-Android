package io.github.ra5on.titanos.plugins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import io.github.ra5on.titanos.net.TitanJson
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bildtests aller Seiten und der wichtigsten Dialoge der Plugins (nur nachgebaute Antworten). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class PluginsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().pluginFixtures().start()
    private val shots = Shots(compose)

    private class Screen(val start: String?, val setup: PluginsState.() -> Unit = {})

    private var screen by mutableStateOf(Screen(null))
    private var round by mutableStateOf(0)

    private fun go(s: Screen, wait: String) {
        screen = s
        round++
        shots.settle()
        shots.untilText(wait)
    }

    private fun cap(name: String, dark: Boolean) {
        shots.capture(name, dark)
        shots.audit.found += wordBreaks(compose, shots.prefix + name)
    }

    private fun capScreen(name: String, dark: Boolean) {
        shots.captureScreen(name, dark)
        shots.audit.found += wordBreaks(compose, shots.prefix + name)
    }

    private fun text(key: String) = io.github.ra5on.titanos.admin.adminTextForTest(key)

    private fun screens(dark: Boolean) {
        val env = nas.env()
        val preview = TitanJson.decodeFromString(Plugin.serializer(), res("inspect.json"))
        shots.show(dark) {
            key(round) {
                val s = screen
                PluginsProgram(env, s.start, {}, pollMs = 0, state = PluginsState(PluginsApi(env.http)).apply(s.setup))
            }
        }
        shots.untilText("Intel GPU Tools")
        cap("01-plugins", dark)
        go(Screen(null) { picked = "wangxun-nic" }, "txgbe: Wangxun SP1000")
        cap("02-wangxun", dark)
        go(Screen(null) { picked = "wangxun-nic"; asking = true; volume = "Data" }, text("plugins.allow"))
        capScreen("03-installieren", dark)
        go(Screen(null) { picked = "community-fan"; asking = true; volume = "Data" }, text("plugins.allow"))
        capScreen("04-installieren-community", dark)
        go(Screen(null) { picked = "intel-gpu-tools" }, "intel_gpu_top")
        cap("05-intel", dark)
        go(Screen(null) { picked = "tailscale" }, "100.110.69.66")
        cap("06-tailscale", dark)
        go(Screen(null) { picked = "netbird" }, text("netbird.setupKey"))
        cap("07-netbird", dark)
        go(Screen(null) { picked = "nvidia" }, "620.12.03".let { text("plugins.choice.title") })
        cap("08-nvidia", dark)
        go(Screen(null) { picked = "werkzeug" }, text("plugins.stepsFailed"))
        cap("09-werkzeug", dark)
        go(Screen(null) { picked = "werkzeug"; updating = UpdateTarget("werkzeug", "Werkzeug", "/mnt/Data/media/werkzeug.plugin.json", "2.0", false, false) }, "/mnt/Data/media/werkzeug.plugin.json")
        capScreen("10-aktualisieren", dark)
        go(Screen(null) { picked = "intel-gpu-tools"; removing = Target("intel-gpu-tools", "Intel GPU Tools") }, text("plugins.removeSubmit"))
        capScreen("11-entfernen", dark)
        go(Screen(null) { linking = true; linkURL = "https://example.org/plugins/linkplugin.plugin.json"; linkPreview = preview; volume = "Data" }, "Link-Plugin")
        capScreen("12-von-link", dark)
        go(Screen(null) { sourcesOpen = true }, "Kaputte Quelle")
        capScreen("13-quellen", dark)
        go(Screen("cleanup"), "0.55.0")
        cap("14-aufraeumen", dark)
        go(Screen(null) { picked = "coral-usb" }, "6.1.0")
        cap("15-coral", dark)
        nas.on("GET", "plugins/progress", """{"operations":[${res("progress-running-op.json")}]}""")
        go(Screen(null) { picked = "wangxun-nic" }, "MB/s")
        cap("16-schritte", dark)
        nas.on("GET", "plugins/progress", res("progress.json"))
    }

    @Test fun hell() = screens(false)
    @Test fun dunkel() = screens(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun breit() { shots.prefix = "breit-"; screens(true) }
    @Test @Config(qualifiers = "de-w840dp-h1000dp-xhdpi") fun split() { shots.prefix = "split-"; screens(true) }
    @Test fun schrift200() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; screens(false) }
    @Test @Config(qualifiers = "en-w390dp-h844dp-xxhdpi") fun englisch() { shots.prefix = "en-"; screens(false) }

    @After fun end() {
        nas.close()
        shots.audit.assertClean()
    }
}
