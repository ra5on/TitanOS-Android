package io.github.ra5on.titanos.watch.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.wear.compose.material3.AppScaffold
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.ra5on.titanos.watch.data.AppNotification
import io.github.ra5on.titanos.watch.data.Camera
import io.github.ra5on.titanos.watch.data.CameraEvent
import io.github.ra5on.titanos.watch.data.Chat
import io.github.ra5on.titanos.watch.data.ChatInfo
import io.github.ra5on.titanos.watch.data.Container
import io.github.ra5on.titanos.watch.data.Entry
import io.github.ra5on.titanos.watch.data.HaEntity
import io.github.ra5on.titanos.watch.data.Pending
import io.github.ra5on.titanos.watch.data.Vm
import io.github.ra5on.titanos.watch.data.VolumeProblem
import io.github.ra5on.titanos.watch.data.WatchStatus
import io.github.ra5on.titanos.watch.pair.CodeContent
import io.github.ra5on.titanos.watch.pair.PairScreen
import io.github.ra5on.titanos.watch.pair.PairView
import io.github.ra5on.titanos.watch.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bildtests der Uhr: jeder Bildschirm rund (227 dp) und eckig (198 dp), mit
 * normaler und doppelter Schrift. Geprüft wird beim Zeichnen: kein Text
 * abgeschnitten, kein Wort getrennt, kein Platzhalter, jedes Bedienelement
 * mit Namen für TalkBack. Bilder: build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var prefix = ""
    private val cut = mutableListOf<String>()

    @Test
    @Config(qualifiers = "de-w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", sdk = [35])
    fun rund() = run("rund")

    @Test
    @Config(qualifiers = "de-w198dp-h198dp-small-notlong-notround-watch-xhdpi-keyshidden-nonav", sdk = [35])
    fun eckig() = run("eckig")

    @Test
    @Config(qualifiers = "de-w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", sdk = [35])
    fun rund200() {
        RuntimeEnvironment.setFontScale(2f)
        run("rund200")
    }

    @Test
    @Config(qualifiers = "de-w198dp-h198dp-small-notlong-notround-watch-xhdpi-keyshidden-nonav", sdk = [35])
    fun eckig200() {
        RuntimeEnvironment.setFontScale(2f)
        run("eckig200")
    }

    @Test
    @Config(qualifiers = "en-w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", sdk = [35])
    fun roundEnglish200() {
        RuntimeEnvironment.setFontScale(2f)
        run("en-rund200")
    }

    private val ok = WatchStatus("titanos", "1.19", 2, emptyList(), 3_100_000_000_000, 8_000_000_000_000, 0, 0, null, 0)
    private val bad = ok.copy(problems = listOf(VolumeProblem("Daten", "degraded")), newMessages = 3, warnings = 1)

    private fun picture(): Bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.rgb(40, 60, 80))
        c.drawCircle(160f, 90f, 50f, Paint().apply { color = Color.rgb(200, 180, 120) })
    }

    private val chat = Chat(
        "c1", "Uhr", "waiting", null,
        Pending("p1", "vm_action", "high", "VM „haos“ neu starten, damit das Update greift."),
        listOf(Entry("u", "user", "Starte Home Assistant neu"), Entry("a", "assistant", "Das Update ist installiert. Für den Neustart brauche ich deine Freigabe.")),
    )

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "koppeln" to { PairScreen() },
        "koppeln-adresse" to { TextEntry("Adresse des NAS", "192.168.178.20", uri = true, onDone = {}, onCancel = {}) },
        "koppeln-zertifikat" to { PairScreen(initial = PairView.Failed(R.string.w_pair_err_cert, "192.168.178.20:443")) },
        "koppeln-abgemeldet" to { PairScreen(unpairedByNas = true) },
        "koppeln-zahl" to { CodeContent("titanos", "482913", listOf("63B5 2A1F 0C9D 7E44", "A1B2 C3D4 E5F6 0718")) },
        "start" to { HomeScreen(ok, 0, null) {} },
        "start-warnung" to { HomeScreen(bad, 2, R.string.w_err_reach) {} },
        "agent" to { AgentScreen(AgentView.Idle, {}, {}, {}, {}, { _, _ -> }, {}) },
        "agent-zuhoeren" to { AgentScreen(AgentView.Recording, {}, {}, {}, {}, { _, _ -> }, {}) },
        "agent-arbeitet" to { AgentScreen(AgentView.Working("Wie voll ist das NAS?"), {}, {}, {}, {}, { _, _ -> }, {}) },
        "agent-freigabe" to { AgentScreen(AgentView.Answer("Starte Home Assistant neu", chat), {}, {}, {}, {}, { _, _ -> }, {}) },
        "freigaben" to { ApprovalsScreen(listOf(chat), null, null) { _, _ -> } },
        "schalten" to {
            SwitchScreen(
                SwitchData(
                    Section.Ready(listOf(HaEntity("light.kueche", "Küchenlicht", "Küche", "on", "light"), HaEntity("switch.pumpe", "Gartenpumpe", null, "off", "switch"), HaEntity("scene.abend", "Abendstimmung", null, "scening", "scene"))),
                    Section.Ready(listOf(Container("immich", "immich-server", "running"), Container("jellyfin", "jellyfin", "exited"))),
                    Section.Ready(listOf(Vm("haos", "running"), Vm("windows11", "shutoff"))),
                ),
                null, null, { _, _ -> }, {}, {},
            )
        },
        "schalten-nichts" to { SwitchScreen(SwitchData(Section.Hidden, Section.Hidden, Section.Hidden), null, null, { _, _ -> }, {}, {}) },
        "kameras" to { CamerasScreen(listOf(Camera("c1", "Haustür"), Camera("c2", "Garten")), null) {} },
        "kamera" to {
            CameraScreen("Haustür", picture(), listOf(CameraEvent("e1", "c1", "person", "Alex", 1_763_800_000.0, true), CameraEvent("e2", "c1", "car", "", 1_763_790_000.0, false)), null, null, {}, {}, {})
        },
        "meldungen" to {
            NotificationsScreen(
                listOf(
                    AppNotification("1-3", "2026-11-22T10:15:00Z", "nas", "warning", "Volume „Daten“ eingeschränkt", "Eine Platte fehlt. Der Speicher läuft ohne Ausfallsicherheit."),
                    AppNotification("1-2", "2026-11-22T09:00:00Z", "agent", "info", "Agent", "Die tägliche Sicherung ist fertig."),
                ),
                null,
            )
        },
        "einstellungen" to { SettingsRoute() },
        "haftungsausschluss" to { DisclaimerRoute() },
        "dialog" to { ConfirmDanger(true, "„windows11“ herunterfahren?", "Die VM fährt geordnet herunter.", "Herunterfahren", {}, {}) },
    )

    private fun run(name: String) {
        prefix = name
        var current by mutableStateOf(0)
        compose.setContent { WatchTheme { AppScaffold { screens[current].second() } } }
        for (i in screens.indices) {
            current = i
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            captureScreenRoboImage("build/outputs/roborazzi/$prefix-${screens[i].first}.png")
            checkAll(screens[i].first)
        }
        check(cut.isEmpty()) { "Abgeschnittene oder getrennte Texte, namenlose Bedienelemente:\n" + cut.distinct().joinToString("\n") }
    }

    /** Durch die ganze Liste scrollen und an jeder Stelle prüfen. */
    private fun checkAll(screen: String) {
        overflow(screen)
        val lists = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
        if (lists.isEmpty()) return
        val count = lists.first().config.getOrElseNullable(SemanticsProperties.CollectionInfo) { null }?.rowCount ?: 0
        for (i in 0 until count) {
            runCatching { compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(i) }
            compose.mainClock.advanceTimeBy(300)
            overflow(screen)
        }
    }

    private fun overflow(screen: String) {
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val cfg = node.config
            val named = cfg.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text.isNotBlank() } == true ||
                cfg.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { it.isNotBlank() } == true
            if (!named && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) cut += "$prefix-$screen: Bedienelement ohne Namen bei ${node.boundsInRoot}"
        }
        for (node in compose.onAllNodes(hasAnyAncestor(isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null } ?: continue
            val out = mutableListOf<TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            // Keine Silbentrennung (Inhaber 21.11.2026 „Ganze Wörter“): automatische Trennung ist ein Fund, auch wenn sie hier nicht greift.
            if (r.layoutInput.style.hyphens == androidx.compose.ui.text.style.Hyphens.Auto) cut += "$prefix-$screen: Silbentrennung eingeschaltet „${text.take(60)}“"
            if (Regex("%\\d?\\$?[sd]|\\{[a-zA-Z]+\\}").containsMatchIn(text)) cut += "$prefix-$screen: Platzhalter „${text.take(60)}“"
            val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
            val bottom = if (r.lineCount > 0) r.getLineBottom(r.lineCount - 1) else 0f
            val dots = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            // Ein einzeiliges Eingabefeld scrollt waagrecht (Prüfregel aller Module, Abschluss I1).
            val editable = node.config.getOrElseNullable(SemanticsProperties.EditableText) { null } != null
            if ((!editable && widest > node.size.width + 2) || bottom > node.size.height + 2 || dots) {
                cut += "$prefix-$screen: „${text.take(60)}“ (${widest.toInt()}×${bottom.toInt()} in ${node.size})"
            }
            // Ganze Wörter: ein Zeilenende mitten zwischen zwei Buchstaben heißt, das Wort wurde getrennt.
            for (line in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(line)
                val before = text.getOrNull(end - 1)
                val after = text.getOrNull(end)
                if (before != null && after != null && before.isLetterOrDigit() && after.isLetterOrDigit()) {
                    cut += "$prefix-$screen: Wort getrennt in „${text.take(60)}“ bei „${text.substring((end - 8).coerceAtLeast(0), (end + 8).coerceAtMost(text.length))}“"
                }
            }
        }
    }
}
