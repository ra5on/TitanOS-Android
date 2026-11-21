package io.github.ra5on.titanos.storage

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
class StorageFlowTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().storageFixtures().start()
    private val shots = Shots(compose)

    @After fun end() = nas.close()

    private fun show(start: String? = null, nav: StorageNav.() -> Unit = {}, state: OverviewState.() -> Unit = {}) {
        val env = nas.env()
        shots.show(false) { StorageProgram(env, start, {}, pollMs = 0, nav = StorageNav(start).apply(nav), overview = OverviewState().apply(state)) }
    }

    private fun click(text: String, last: Boolean = false) {
        val nodes = compose.onAllNodes(hasText(text, substring = false) and hasClickAction())
        (if (last) nodes.onLast() else nodes.onFirst()).performScrollTo().performClick()
        shots.settle()
    }

    private fun sent(method: String, path: String) = nas.seen.lastOrNull { it.method == method && it.path == path }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun deleteSendsVolumeNameItself() {
        show(state = { selected = "Old"; dialog = OverviewDialog.Delete("Old") })
        shots.untilText("Endgültig löschen")
        click("Endgültig löschen")
        shots.until { sent("DELETE", "storage/volumes/Old") != null }
        assertEquals(json("""{"confirm":"Old"}"""), json(sent("DELETE", "storage/volumes/Old")!!.body))
    }

    @Test fun unlockSendsPasswordButNeverLogsIt() {
        val secret = UUID.randomUUID().toString()
        show(state = { selected = "Vault" })
        shots.untilText("wartet auf Passwort")
        click("Entsperren")
        shots.untilText("Passwort")
        compose.onAllNodes(hasSetTextAction() and hasContentDescription("Passwort")).onFirst().performTextInput(secret)
        shots.settle()
        click("Entsperren", last = true)
        shots.until { sent("POST", "storage/volumes/Vault/unlock") != null }
        assertEquals(json("""{"password":"$secret"}"""), json(sent("POST", "storage/volumes/Vault/unlock")!!.body))
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains(secret) == true || it.throwable?.toString()?.contains(secret) == true })
    }

    @Test fun syncSpeedApplies() {
        show(state = { selected = "Data" })
        shots.untilText("Schnell")
        click("Schnell")
        shots.until { sent("PUT", "storage/volumes/Data/sync-speed") != null }
        assertEquals(json("""{"speed":"fast"}"""), json(sent("PUT", "storage/volumes/Data/sync-speed")!!.body))
    }

    @Test fun removeDiskAsksPlainly() {
        show(state = { selected = "Old"; dialog = OverviewDialog.Remove("Old", "sdi") })
        shots.untilText("Entfernen")
        click("Entfernen")
        shots.until { sent("DELETE", "storage/volumes/Old/disks/sdi") != null }
    }

    @Test fun profileChange() {
        show(state = { selected = "Fast"; dialog = OverviewDialog.Profile("Fast") })
        val single = io.github.ra5on.titanos.admin.adminTextForTest("level.single")
        shots.untilText(single)
        compose.onAllNodes(hasText(single, substring = true) and hasClickAction()).onFirst().performScrollTo().performClick()
        shots.settle()
        click(io.github.ra5on.titanos.admin.adminTextForTest("btrfs.profileSubmit"), last = true)
        shots.until { sent("PUT", "storage/volumes/Fast/profile") != null }
        assertEquals(json("""{"profile":"single"}"""), json(sent("PUT", "storage/volumes/Fast/profile")!!.body))
    }

    @Test fun cacheModeChange() {
        show(state = { selected = "Data" })
        shots.untilText("Modus ändern")
        click("Modus ändern")
        click("Modus ändern", last = true)
        shots.until { sent("PUT", "storage/volumes/Data/cache") != null }
        assertEquals(json("""{"mode":"writethrough"}"""), json(sent("PUT", "storage/volumes/Data/cache")!!.body))
    }

    @Test fun volumeSleepSavesOnlyKnownTargets() {
        show(state = { selected = "Data" })
        shots.untilText("Ändern …")
        click("Ändern …")
        click("Speichern")
        shots.until { sent("PUT", "storage/disk-sleep") != null }
        assertEquals(
            json("""{"idleMinutes":15,"volumes":{"Data":30},"disks":{"serial:WD-J1":60}}"""),
            json(sent("PUT", "storage/disk-sleep")!!.body),
        )
    }

    @Test fun importRegistersWithName() {
        show("import")
        shots.untilText("md:fixture")
        click("Lesend prüfen")
        shots.untilText("Photos")
        compose.onAllNodes(hasSetTextAction() and hasContentDescription("Name")).onFirst().performTextInput("Archiv")
        shots.settle()
        click("Volume registrieren")
        shots.until { sent("POST", "storage/import") != null }
        assertEquals(json("""{"id":"md:fixture","fingerprint":"fingerprint","name":"Archiv","confirm":"Archiv"}"""), json(sent("POST", "storage/import")!!.body))
    }

    @Test fun replaceDegradedVolumeAddsNewDisk() {
        show("replace", nav = { volume = "Old" })
        shots.untilText("Weiter")
        click("Weiter")
        shots.untilText("sdj")
        compose.onAllNodes(hasText("sdj", substring = false) and hasClickAction()).onFirst().performScrollTo().performClick()
        shots.settle()
        click("Tausch starten")
        click("Löschen und tauschen")
        shots.until { sent("POST", "storage/volumes/Old/disks") != null }
        assertEquals(json("""{"disk":"sdj","erase":true,"identity":"serial:WD-J1"}"""), json(sent("POST", "storage/volumes/Old/disks")!!.body))
    }

    @Test fun growthPendingCancelSendsVolume() {
        nas.on("GET", "storage/growth", res("growth-pending.json"))
        show("manage", nav = { volume = "Data"; mode = "grow" })
                val cancel = io.github.ra5on.titanos.admin.adminTextForTest("ops.cancelGrowth")
        shots.untilText(cancel)
        click(cancel)
        click(cancel, last = true)
        shots.until { sent("POST", "storage/growth/cancel") != null }
        assertEquals(json("""{"confirm":"Data"}"""), json(sent("POST", "storage/growth/cancel")!!.body))
    }
}
