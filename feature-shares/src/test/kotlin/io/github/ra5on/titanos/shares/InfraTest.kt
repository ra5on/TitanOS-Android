package io.github.ra5on.titanos.shares

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ra5on.titanos.admin.AdminShell
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SideEntry
import io.github.ra5on.titanos.admin.SideGroup
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class InfraTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().start()
    private val shots = Shots(compose)

    @Test fun fixtures() = runBlocking {
        val shares = nas.env().http.json("shares")
        check(shares is JsonArray || shares.toString().contains("name")) { shares.toString() }
    }

    private fun screens(dark: Boolean) {
        shots.show(dark) {
            AdminShell("shares", "yellow", t("desktop.app.shares"), listOf(SideGroup(null, listOf(SideEntry("a", t("desktop.side.shares"), "globe"), SideEntry("b", t("shares.new"), "plus")))), "a", {}, {}) {
                PageBody {
                    SettingGroup(t("shares.new")) {
                        SettingRow(t("desktop.side.shares"), desc = t("common.loading"), icon = "folder", chevron = true, onClick = {})
                        ToggleRow(t("common.save"), true, {}, divider = true)
                    }
                }
            }
        }
        shots.capture("00-rahmen", dark)
    }

    @Test fun hell() = screens(false)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun breit() { shots.prefix = "breit-"; screens(true) }

    @After fun end() { nas.close(); shots.audit.assertClean() }
}
