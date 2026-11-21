package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.files.DeleteDialog
import io.github.ra5on.titanos.files.Direction
import io.github.ra5on.titanos.files.FilesHomeScreen
import io.github.ra5on.titanos.files.FolderScreen
import io.github.ra5on.titanos.files.EntrySheet
import io.github.ra5on.titanos.files.FolderMenu
import io.github.ra5on.titanos.files.FolderState
import io.github.ra5on.titanos.files.Location
import io.github.ra5on.titanos.files.RootsState
import io.github.ra5on.titanos.files.Transfer
import io.github.ra5on.titanos.files.TransferState
import io.github.ra5on.titanos.files.WaitReason
import io.github.ra5on.titanos.files.OfflinePin
import io.github.ra5on.titanos.files.OfflineScreen
import io.github.ra5on.titanos.files.OfflineStore
import io.github.ra5on.titanos.files.Incoming
import io.github.ra5on.titanos.files.ShareModel
import io.github.ra5on.titanos.files.ShareTargetScreen
import io.github.ra5on.titanos.files.TransfersScreen
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.home.HomeState
import io.github.ra5on.titanos.home.visiblePrograms
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.model.Volume
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.ProbedCertificate
import io.github.ra5on.titanos.net.Spki
import io.github.ra5on.titanos.pairing.AddressCheck
import io.github.ra5on.titanos.pairing.CheckStatus
import io.github.ra5on.titanos.pairing.ConfirmScreen
import io.github.ra5on.titanos.pairing.ConfirmState
import io.github.ra5on.titanos.pairing.Grant
import io.github.ra5on.titanos.pairing.ManualScreen
import io.github.ra5on.titanos.pairing.ManualState
import io.github.ra5on.titanos.pairing.PairPhase
import io.github.ra5on.titanos.pairing.PermissionsScreen
import io.github.ra5on.titanos.pairing.PermissionsState
import io.github.ra5on.titanos.pairing.WelcomeScreen
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.BottomNav
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Bildtests der Bildschirme in 412 dp Breite (wie die Entwürfe), hell und
 * dunkel. Beispieldaten nur hier. Bilder: `./gradlew recordRoborazziDebug`
 * schreibt sie nach app-titanos/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class ScreenshotTest {

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    @Composable
    private fun NavBar(selected: String) {
        BottomNav(
            listOf(
                NavItem("Home", stringResource(UiR.string.ui_nav_home), TitanIcons.home),
                NavItem("Files", stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
                NavItem("Photos", stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
                NavItem("Notifications", stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell),
            ),
            selected,
        ) {}
    }

    private val lanSpki = Spki.encode(byteArrayOf(0x4F, 0x2A, 0x91.toByte(), 0xC7.toByte(), 0x0E, 0x3B, 0x6D, 0x58, 0xA1.toByte(), 0xF0.toByte(), 0x22, 0xB9.toByte(), 0x7C, 0x4E, 0x1D, 0x06) + ByteArray(16) { 7 })

    @Test
    fun s01_welcome() = shot("01-willkommen") { WelcomeScreen({}, {}) }

    @Test
    fun s03_confirm() = shot("03-koppeln-bestaetigen") {
        ConfirmScreen(
            ConfirmState(
                hostname = "nas",
                addresses = listOf(
                    AddressCheck("nas.tail1234.ts.net", AddressKind.TAILSCALE, CheckStatus.Reachable),
                    AddressCheck("192.168.1.20", AddressKind.LAN, CheckStatus.Reachable),
                ),
                fingerprint = Spki.displayLines(Spki.decode(lanSpki)!!).joinToString(" "),
                deviceName = "Pixel 9 von Zeus",
                phase = PairPhase.Waiting,
            ),
            {}, {}, {},
        )
    }

    @Test
    fun s04_permissions() = shot("04-berechtigungen") {
        PermissionsScreen(PermissionsState(localNetwork = Grant.Granted, notifications = Grant.NotAsked, contacts = Grant.Granted, calendar = Grant.NotAsked), {}, {}, {}, onBack = {})
    }

    @Test
    fun s05_manual() = shot("05-adresse-von-hand") {
        ManualScreen(
            ManualState(address = "192.168.1.20", probed = ProbedCertificate(lanSpki, Spki.decode(lanSpki)!!, false), probedFor = "192.168.1.20", code = "K7Q4-9XMT"),
            {}, {}, {}, {}, {}, {},
        )
    }

    private val home = HomeState(
        hostname = "nas",
        loading = false,
        volumes = listOf(Volume("Data", "raid1", "btrfs", "ok", true, 8_000_000_000_000, 4_100_000_000_000)),
        uptimeSeconds = 3 * 86_400 + 5_000,
        programs = visiblePrograms("owner", mapOf("mail" to true, "calendar" to true, "contacts" to true)),
    )

    @Test
    fun s10_home() = shot("10-start") {
        Column(Modifier.fillMaxSize()) {
            HomeScreen(home, {}, {}, {}, {}, Modifier.weight(1f))
            NavBar("Home")
        }
    }

    @Test
    fun s10_home_problem() = shot("10-start-befund") {
        Column(Modifier.fillMaxSize()) {
            HomeScreen(
                home.copy(volumes = listOf(Volume("Data", "raid1", "btrfs", "degraded", true, 8_000_000_000_000, 4_100_000_000_000)), programs = visiblePrograms("user", emptyMap())),
                {}, {}, {}, {}, Modifier.weight(1f),
            )
            NavBar("Home")
        }
    }

    private fun root(name: String, free: Long, total: Long) =
        FileRoot("share/$name", name, "share", writable = true, recycle = true, recycleDays = 30, totalBytes = total, freeBytes = free)

    @Test
    fun s20_files() = shot("20-dateien-freigaben") {
        FilesHomeScreen(
            RootsState(listOf(root("Documents", 3_900_000_000_000, 8_000_000_000_000), root("Media", 3_900_000_000_000, 8_000_000_000_000), root("Fotos", 3_900_000_000_000, 8_000_000_000_000), root("Projects", 400_000_000_000, 1_000_000_000_000))),
            running = 1, finished = 4, offlineSummary = "3 Ordner, 14 Dateien · 1,2 GB", onRoot = {}, onTransfers = {}, onOffline = {}, onSearch = {}, onTrash = {}, onJobs = {}, bottomBar = { NavBar("Files") },
        )
    }

    private val now: Instant = Instant.now()
    private val entries = listOf(
        FileEntry("Rechnungen", dir = true, modified = now.minus(20, ChronoUnit.DAYS).toString()),
        FileEntry("Steuer 2025", dir = true, modified = now.minus(1, ChronoUnit.DAYS).toString()),
        FileEntry("Verträge", dir = true, modified = now.minus(40, ChronoUnit.DAYS).toString()),
        FileEntry("Mietvertrag Wohnung.pdf", size = 2_400_000, modified = "2026-11-03T10:00:00Z"),
        FileEntry("Haushaltsbuch 2026.xlsx", size = 86_000, modified = now.minus(1, ChronoUnit.DAYS).toString()),
        FileEntry("Ausweis Scan.jpg", size = 1_100_000, modified = "2026-10-12T10:00:00Z"),
    )
    private val location = Location(root("Documents", 1, 2), "Privat")
    private val upload = Transfer(1, "Kfz-Versicherung Beitragsrechnung 2027.pdf", Direction.Upload, "Documents/Privat", "share/Documents", "Privat", done = 192_000, total = 310_000, state = TransferState.Running)

    @Test
    fun s21_folder() = shot("21-dateien-ordner") {
        FolderScreen(folderUi(listOf(upload)), null, noCallbacks, bottomBar = { NavBar("Files") })
    }

    private fun folderUi(uploads: List<Transfer> = emptyList()) = io.github.ra5on.titanos.files.FolderUi(FolderState(location, entries), entries, uploads = uploads)

    private val noCallbacks = io.github.ra5on.titanos.files.FolderCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun s22_actions() = shot("22-datei-aktionen") {
        FolderScreen(folderUi(), null, noCallbacks, bottomBar = { NavBar("Files") }) {
            EntrySheet(
                listOf(entries[3]), withTrash = true, writable = true, offline = true, officeName = "T-PDF",
                onOffline = {}, onOpen = {}, onOffice = {}, onOpenWith = {}, onShare = {}, onDownload = {}, onCopy = {}, onCut = {},
                onRename = {}, onBatchRename = null, onEdit = null, onExtract = null, onProperties = {}, onDelete = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun s22_delete() = shot("22-datei-papierkorb") {
        FolderScreen(folderUi(), null, noCallbacks, bottomBar = { NavBar("Files") }) {
            DeleteDialog("Mietvertrag Wohnung.pdf", withTrash = true, {}, {})
        }
    }

    @Test
    fun s23_transfers() = shot("23-uebertragungen") {
        TransfersScreen(
            listOf(
                upload,
                Transfer(2, "Urlaub Gardasee.zip", Direction.Upload, "Media/Reisen", "share/Media", "Reisen", 1_800_000_000, 1_800_000_000, TransferState.Done, finishedAtMs = now.toEpochMilli()),
                Transfer(3, "Steuerbescheid.pdf", Direction.Download, "Documents", "share/Documents", "", 120_000, 120_000, TransferState.Done, finishedAtMs = now.minus(1, ChronoUnit.DAYS).toEpochMilli()),
                Transfer(4, "Präsentation.key", Direction.Upload, "Projects", "share/Projects", "", 2_000_000, 9_000_000, TransferState.Failed, offline = true, finishedAtMs = now.toEpochMilli()),
            ),
            onBack = {}, onCancel = {}, now = now,
        )
    }

    @Test
    fun s23b_transfers_waiting() = shot("23-uebertragungen-wartet") {
        TransfersScreen(
            listOf(
                Transfer(5, "IMG_2041.mp4", Direction.Upload, "Fotos/Handy", "share/Fotos", "Handy", 0, 80_000_000, TransferState.Waiting, waiting = WaitReason.Network),
                Transfer(6, "Rechnung.pdf", Direction.Upload, "Documents", "share/Documents", "", 0, 90_000, TransferState.Waiting, waiting = WaitReason.Nas),
                Transfer(4, "Präsentation.key", Direction.Upload, "Projects", "share/Projects", "", 2_000_000, 9_000_000, TransferState.Failed, offline = true, finishedAtMs = now.toEpochMilli()),
            ),
            onBack = {}, onCancel = {}, now = now,
        )
    }

    private fun offlineStore(): OfflineStore {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "offline-shot-" + System.nanoTime())
        return OfflineStore(dir).apply {
            pin("share/Documents", "Documents", "Steuer 2025", dir = true)
            pin("share/Media", "Media", "Noten Chor", dir = true)
            pin("share/Documents", "Documents", "Privat/Mietvertrag Wohnung.pdf", dir = false)
            finish(
                listOf(
                    OfflinePin("share/Documents", "Documents", "Steuer 2025", true, files = 8, bytes = 46_000_000),
                    OfflinePin("share/Media", "Media", "Noten Chor", true, files = 5, bytes = 12_000_000, problem = "offline"),
                    OfflinePin("share/Documents", "Documents", "Privat/Mietvertrag Wohnung.pdf", false, files = 1, bytes = 2_400_000),
                ),
            )
        }
    }

    @Test
    fun s24_offline() = shot("24-offline") {
        OfflineScreen(offlineStore(), onBack = {}, onPin = {}, onRemove = {}, onSyncNow = {}, onWifiOnly = {}, bottomBar = { NavBar("Files") })
    }

    @Test
    fun s24b_folder_menu() = shot("24-ordner-offline") {
        FolderScreen(folderUi(), null, noCallbacks, bottomBar = { NavBar("Files") }) {
            FolderMenu(
                "Privat", favorite = false, offline = true, hasTrash = true, onFavorite = {}, onOffline = {}, onSelectAll = {}, onProperties = {},
                onTrash = {}, onJobs = {}, onUsage = {}, onDuplicates = {}, onReload = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun s81_share() = shot("81-teilen-ziel") {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("share", android.content.Context.MODE_PRIVATE).edit()
            .putString("root", "share/Fotos").putString("rootName", "Fotos").putString("path", "Handy Zeus/Geteilt").commit()
        val api = io.github.ra5on.titanos.net.TitanApi(io.github.ra5on.titanos.net.NasConnection(emptyList(), null))
        val items = listOf("IMG_2041.jpg" to 3_100_000L, "IMG_2042.jpg" to 2_900_000L, "IMG_2043.jpg" to 3_400_000L)
            .map { (n, b) -> Incoming(android.net.Uri.parse("content://x/$n"), n, b) }
        ShareTargetScreen(ShareModel(ctx, api, items), onCancel = {}, onUpload = {}, onOpen = {})
    }
}
