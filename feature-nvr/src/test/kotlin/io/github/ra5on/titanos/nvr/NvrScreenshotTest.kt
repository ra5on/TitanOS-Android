package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.Section
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/** Bilder und Live-Bilder ohne Netz: Verläufe wie die Bilder des Webs in den Handy-Entwürfen. */
private object FakeMedia : NvrMedia {
    private fun brush(seed: String): Brush {
        val green = seed.hashCode() % 2 == 0
        return Brush.verticalGradient(if (green) listOf(Color(0xFF3F6B3A), Color(0xFF1E2E1C)) else listOf(Color(0xFF6B3A5E), Color(0xFF2A1A26)))
    }

    @Composable
    override fun Picture(path: String, description: String?, modifier: Modifier, contain: Boolean, refreshKey: Any?, onSize: (Int, Int) -> Unit, onError: () -> Unit) {
        LaunchedEffect(path) { onSize(1280, 720) }
        Box(modifier.background(brush(path)))
    }

    @Composable
    override fun Live(camera: String, name: String, sub: Boolean, sound: Boolean, contain: Boolean, height: Int, modifier: Modifier, onShown: (LiveShown) -> Unit) {
        LaunchedEffect(camera) { onShown(LiveShown(if (camera == "terrace") LiveMode.None else LiveMode.Still)) }
        if (camera != "terrace") Box(modifier.background(brush(camera)))
    }

    @Composable
    override fun Video(state: VideoState, description: String, modifier: Modifier) {
        Box(modifier.background(Color(0xFF222222)), contentAlignment = Alignment.Center) {}
    }
}

/**
 * Bildtests der NVR-Seiten mit Beispieldaten: hell und dunkel bei 390 dp,
 * Schrift 200 % und breit 1280 dp. `./gradlew :feature-nvr:recordRoborazziDebug`
 * schreibt sie nach feature-nvr/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xhdpi")
class NvrScreenshotTest {
    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun font() = org.robolectric.RuntimeEnvironment.setFontScale(1f)

    private val now = System.currentTimeMillis() / 1000.0
    private val today = LocalDate.now()
    private val midnight = dayStart(today).toDouble()

    private fun cam(id: String, name: String, order: Int, online: Boolean = true, audio: Boolean = false, ptz: Boolean = false, twoWay: Boolean = false) = Camera(
        id = id, name = name, enabled = true, order = order, audio = CameraAudio(audio, twoWay), ptz = ptz,
        state = CameraState(online, true, 15.0, 1.2, if (online) null else java.time.Instant.ofEpochSecond((now - 1800).toLong()).toString()),
        source = CameraSource("rtsp", "192.168.1.${60 + order}", 554, 0, "viewer", true, "rtsp://192.168.1.${60 + order}:554/main", "rtsp://192.168.1.${60 + order}:554/sub", mainWidth = 2560, mainHeight = 1440, subWidth = 640, subHeight = 360),
        detect = CameraDetect(true, listOf("person", "car"), plates = order == 2, faces = order == 1),
        record = CameraRecord("events", 14, 14, 14, 5, 5),
        notify = CameraNotify(titan = true, kinds = listOf("person", "offline"), recipients = listOf(NotifyRecipient("owner", bell = true, push = true)), pauseSeconds = 60, window = NotifyWindow(true, listOf(1, 2, 3, 4, 5), "22:00", "06:00")),
        zones = if (order == 1) listOf(CameraZone("weg", "Weg", listOf(listOf(0.1, 0.6), listOf(0.5, 0.55), listOf(0.6, 0.95), listOf(0.15, 0.95)))) else emptyList(),
        masks = CameraMasks(listOf(CameraMask(listOf(listOf(0.7, 0.05), listOf(0.95, 0.05), listOf(0.95, 0.3)))), emptyList()),
    )

    private val cameras = listOf(
        cam("door", "Haustür", 1, audio = true, twoWay = true),
        cam("drive", "Einfahrt", 2),
        cam("garden", "Garten", 3, ptz = true),
        cam("terrace", "Terrasse", 4, online = false),
    )
    private val removed = Camera(id = "old", name = "Alte Garage", deleted = true, deletedAt = JsonPrimitive(now - 3 * 86400), expiresAt = JsonPrimitive(now + 11 * 86400))
    private val hardware = NvrHardware(
        listOf(NvrDevice("npu", "Intel AI Boost", true, true), NvrDevice("igpu", "Intel Arc", true, true), NvrDevice("coral", "Coral USB", true, false, "plugin"), NvrDevice("cpu", "CPU", true, true)),
        "npu",
        listOf(NvrDecoder("0000:00:02.0", "Intel Arc", "igpu", true), NvrDecoder("0000:01:00.0", "NVIDIA RTX A2000", "nvidia", false, "vm")),
        videoDecode = true, accelDevice = true, avx2 = true, memoryBytes = 32L shl 30, enrichmentsPossible = true,
    )
    private val status = NvrStatus(
        true, "running", CameraCounts(4, 4, 3), true,
        detector = NvrDetector("auto", "igpu", NvrFailover("npu", "igpu", java.time.Instant.ofEpochSecond((now - 3600).toLong()).toString(), "npu_firmware_old", "firmware 1.2"), 12.4),
        storage = NvrStorage(412_000_000_000, 500, 400_000_000_000, 12_000_000_000, 9.5, "ok", now - 7200),
        volume = "Data", hardware = hardware, decoder = NvrDecoderStatus("auto", "0000:00:02.0", "igpu", "Intel Arc"),
    )
    private fun event(n: Int, camera: String, label: String, ago: Double, sub: String = "", plate: String = "", reviewed: Boolean = false, kept: Boolean = false, running: Boolean = false) =
        NvrEvent("${now - ago}-e$n", camera, label, sub, plate, 0.87, now - ago, if (running) null else now - ago + 14, true, true, emptyList(), reviewed, kept, if (kept) "x$n" else null)
    private val events = listOf(
        event(1, "door", "person", 600.0, "Patrick", running = true),
        event(2, "drive", "car", 3600.0, "Unser Auto", "K-TI 2026"),
        event(3, "garden", "person", 7200.0),
        event(4, "garden", "cat", 10800.0, reviewed = true),
        event(5, "door", "person", 36000.0, reviewed = true, kept = true),
    )
    private val activity = mapOf("door" to Activity(true, listOf("person")))

    private fun shot(name: String, sections: List<String> = listOf("live", "recordings", "events", "cameras", "settings", "diagnosis"), selected: String = "live", content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) {
                    CompositionLocalProvider(LocalNvrMedia provides FakeMedia) {
                        val icons = mapOf("live" to TitanIcons.video, "recordings" to TitanIcons.play, "events" to TitanIcons.bell, "cameras" to TitanIcons.grid, "rights" to TitanIcons.user, "settings" to TitanIcons.gear, "diagnosis" to TitanIcons.info)
                        val labels = mapOf("live" to "Live", "recordings" to "Aufnahmen", "events" to "Ereignisse", "cameras" to "Kameras", "rights" to "Kamerarechte", "settings" to "Einstellungen", "diagnosis" to "Diagnose")
                        ProgramShell("NVR", "nvr", "red", sections.map { Section(it, labels.getValue(it), icons.getValue(it)) }, selected, {}, {}) {
                            Box(Modifier.fillMaxSize()) { content() }
                        }
                    }
                }
            }
        }
    }

    private fun big(name: String, selected: String = "live", content: @Composable () -> Unit) {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            shot(name, selected = selected, content = content)
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Composable
    private fun liveView(prefs: NvrPrefs = NvrPrefs(), admin: Boolean = true) {
        androidx.compose.foundation.layout.Column {
        StateBannerView(status, admin, compact = true, {}, {}, {}, {})
        LiveScreen(cameras, cameras.size, admin, true, true, prefs, activity, events, true, { id -> cameras.firstOrNull { it.id == id }?.name ?: id }, null, LiveUi(), {}, {}, {}, {}, {})
        }
    }

    @Test
    fun live() = shot("nvr-live") { liveView() }

    @Test
    fun liveBigFont() = big("nvr-live-schrift-200") { liveView() }

    // Leiste unten mit allen 7 Abschnitten (Auftrag I3): alle Reiter gleich, kleinere Schrift oder „Mehr“, nie ohne Beschriftung.
    private val seven = listOf("live", "recordings", "events", "cameras", "rights", "settings", "diagnosis")

    @Test
    fun sevenTabs() = shot("nvr-leiste-7", sections = seven) { liveView() }

    @Test
    fun sevenTabsBigFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            shot("nvr-leiste-7-schrift-200", sections = seven, selected = "diagnosis") { DiagnosisScreen(diagnosis, null, false) {} }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun liveWithEvents() = shot("nvr-live-ereignisse") { liveView(NvrPrefs(events = true)) }

    @Test
    fun banner() = shot("nvr-ausweichen", selected = "settings") {
        StateBannerView(status, true, compact = false, {}, {}, {}, {})
    }

    @Test
    fun events() = shot("nvr-ereignisse", selected = "events") {
        EventsScreen(cameras, listOf(removed), EventFilter(), events, false, false, null, true, false, { id -> cameras.firstOrNull { it.id == id }?.name ?: id }, {}, {}, {}, {})
    }

    @Test
    fun eventDetail() = shot("nvr-ereignis", selected = "events") {
        EventDetail(events[1], "Einfahrt", true, 14, 5, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun eventsBigFont() = big("nvr-ereignisse-schrift-200", selected = "events") {
        EventsScreen(cameras, emptyList(), EventFilter(list = true), events, false, false, null, false, false, { it }, {}, {}, {}, {})
    }

    private val segments = (0 until 40).map { i -> Segment(midnight + 6 * 3600 + i * 600.0, midnight + 6 * 3600 + i * 600.0 + 540, if (i % 3 == 0) 1.0 else 0.0, if (i % 7 == 0) 1.0 else 0.0) }
    private val exports = listOf(
        NvrExport("e1", "door", "Paketbote", now - 7200, now - 7080, now - 3600, "done", 18_200_000, "owner"),
        NvrExport("e2", "drive", "Einfahrt am Nachmittag mit einem ziemlich langen Namen", now - 3000, now - 1200, now - 600, "running", null, "owner"),
        NvrExport("e3", "garden", "Lücke", now - 90000, now - 89400, now - 86000, "failed", null, "owner"),
        NvrExport("e4", "door", "Haustür (behalten)", now - 36000, now - 35940, now - 35000, "done", 3_100_000, "owner", event = "e5"),
    )

    @Composable
    private fun recordingsView() = RecordingsScreen(
        cameras, listOf(removed), "drive", today, listOf(today.minusDays(3).toString()), DayData(segments, events.filter { it.camera == "drive" }), midnight + 12 * 3600, "24", "1", null, null, false, VideoState(),
        exports, false, true, null, { id -> cameras.firstOrNull { it.id == id }?.name ?: id }, {}, {}, {}, {}, {}, {}, {}, {}, {},
    )

    @Test
    fun recordings() = shot("nvr-aufnahmen", selected = "recordings") { recordingsView() }

    @Test
    fun recordingsBigFont() = big("nvr-aufnahmen-schrift-200", selected = "recordings") { recordingsView() }

    @Test
    fun cameras() = shot("nvr-kameras", selected = "cameras") {
        CamerasScreen(cameras, listOf(removed), true, "", null, 0, CameraActions())
    }

    private val extras = FormExtras(
        RecipientsAnswer(listOf(RecipientAccount("owner", true, true, 2, 0, listOf("door")), RecipientAccount("anna", false, false, 0, 1, listOf("door"))), mailServer = true),
        haConnections = listOf(HAConnection("ha", "Home Assistant")),
    )

    @Test
    fun cameraForm() = shot("nvr-kamera-aendern", selected = "cameras") {
        CameraForm(cameras[0], hardware, listOf("person", "car", "bicycle", "dog", "cat"), listOf("Einfahrt"), "owner", null, {}, {}, extras)
    }

    @Test
    fun cameraFormBigFont() = big("nvr-kamera-aendern-schrift-200", selected = "cameras") {
        CameraForm(cameras[0], hardware, listOf("person", "car"), emptyList(), "owner", null, {}, {}, extras)
    }

    @Test
    fun addCamera() = shot("nvr-kamera-hinzufuegen", selected = "cameras") {
        CameraForm(null, hardware, listOf("person", "car"), emptyList(), "owner", null, {}, {}, extras)
    }

    private val settings = NvrSettings(true, "Data", DetectorChoice("auto"), StorageLimit(500), true, true, "frigate", "geheim-1234", listOf(PlateEntry("Unser Auto", listOf("K-TI 2026"))), false, "auto")

    @Test
    fun settings() = shot("nvr-einstellungen", selected = "settings") {
        SettingsScreen(SettingsData(settings, status, listOf(StorageVolume("Data", true, 4_000_000_000_000, 1_200_000_000_000)), cameras, Faces(true, listOf(Face("Patrick", listOf("a.webp"))), listOf(FaceAttempt("x.webp", now - 600, "Patrick", 0.71))), host = "titan.local"), false, null, false, SettingsActions())
    }

    @Test
    fun settingsBigFont() = big("nvr-einstellungen-schrift-200", selected = "settings") {
        SettingsScreen(SettingsData(settings, status, emptyList(), cameras, null, true, host = "titan.local"), false, null, true, SettingsActions())
    }

    private val diagnosis = Diagnosis(
        "running", DiagContainer("running", 0, java.time.Instant.ofEpochSecond((now - 7200).toLong()).toString(), null, 1), "ghcr.io/blakeblackshear/frigate:0.16.2", "0.16.2", true, "0.16.2",
        status.detector!!, 412, NvrLastError("npu_firmware_old", "firmware 1.2", ""),
        listOf("2026-11-21 10:00:01 frigate.app INFO : Starting Frigate", "2026-11-21 10:00:04 detector.npu WARNING : NPU firmware too old, falling back to the iGPU, which takes a little longer per frame"),
        "frigate", true, listOf("intel_vpu firmware 20240611"), null, null,
        cameras.map { DiagCamera(it.id, it.name, it.state.online, true, 15.0, 5.0, it.state.offlineSince, 5.0, 0.0) }, DiagShm(512.0, 120.0, 392.0), hardware, status.decoder,
    )

    @Test
    fun diagnosis() = shot("nvr-diagnose", selected = "diagnosis") { DiagnosisScreen(diagnosis, null, false) {} }

    @Test
    fun setup() = shot("nvr-einrichtung", selected = "live") {
        SetupScreen(status.copy(enabled = false, state = "off"), listOf(StorageVolume("Data", true, 4_000_000_000_000, 1_200_000_000_000)), SetupForm("Data", "auto", 500, false), true, false, null, {}, {}, {})
    }

    @Test
    fun liveUser() = shot("nvr-live-benutzer", sections = listOf("live", "recordings", "events")) { liveView(admin = false) }

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-mdpi")
    fun wideLive() = shot("nvr-live-breit") { liveView(NvrPrefs(events = true)) }

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-mdpi")
    fun wideSettings() = shot("nvr-einstellungen-breit", selected = "settings") {
        SettingsScreen(SettingsData(settings, status, emptyList(), cameras, null, true, host = "titan.local"), false, null, false, SettingsActions())
    }
}
