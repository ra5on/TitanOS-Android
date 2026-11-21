package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.files.AddSheet
import io.github.ra5on.titanos.files.Clip
import io.github.ra5on.titanos.files.Conflict
import io.github.ra5on.titanos.files.EntrySheet
import io.github.ra5on.titanos.files.Favorite
import io.github.ra5on.titanos.files.FileView
import io.github.ra5on.titanos.files.FilesHomeScreen
import io.github.ra5on.titanos.files.FolderCallbacks
import io.github.ra5on.titanos.files.FolderScreen
import io.github.ra5on.titanos.files.FolderState
import io.github.ra5on.titanos.files.FolderUi
import io.github.ra5on.titanos.files.Found
import io.github.ra5on.titanos.files.Inspection
import io.github.ra5on.titanos.files.Job
import io.github.ra5on.titanos.files.JobsScreen
import io.github.ra5on.titanos.files.Location
import io.github.ra5on.titanos.files.MarkdownView
import io.github.ra5on.titanos.files.PasteDialog
import io.github.ra5on.titanos.files.Properties
import io.github.ra5on.titanos.files.PropertiesSheet
import io.github.ra5on.titanos.files.RootsState
import io.github.ra5on.titanos.files.SearchResult
import io.github.ra5on.titanos.files.SearchScreen
import io.github.ra5on.titanos.files.SearchUi
import io.github.ra5on.titanos.files.SortBy
import io.github.ra5on.titanos.files.SortSheet
import io.github.ra5on.titanos.files.TrashEntry
import io.github.ra5on.titanos.files.TrashScreen
import io.github.ra5on.titanos.files.TrashUi
import io.github.ra5on.titanos.files.UndoKind
import io.github.ra5on.titanos.files.UndoOffer
import io.github.ra5on.titanos.files.UrlDialog
import io.github.ra5on.titanos.files.UrlProbe
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.photos.GalleryFilter
import io.github.ra5on.titanos.photos.GalleryScreen
import io.github.ra5on.titanos.photos.GalleryState
import io.github.ra5on.titanos.photos.LayoutChoice
import io.github.ra5on.titanos.photos.MonthFormat
import io.github.ra5on.titanos.photos.NasPhoto
import io.github.ra5on.titanos.photos.PhotoSettings
import io.github.ra5on.titanos.photos.PhotosSettingsScreen
import io.github.ra5on.titanos.photos.Structure
import io.github.ra5on.titanos.files.PhotoEntry
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.BottomNav
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.TopBar
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
 * Bildtests der neuen Dateiseiten (Ordner, Raster, Auswahl, Blätter,
 * Papierkorb, Suche, Aufträge, Eigenschaften) und der Foto-Einstellungen,
 * hell und dunkel, in Handybreite 390 dp. Beispieldaten nur hier.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xhdpi")
class FilesScreenshotTest {

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
    private fun NavBar() {
        BottomNav(
            listOf(
                NavItem("Home", stringResource(UiR.string.ui_nav_home), TitanIcons.home),
                NavItem("Files", stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
                NavItem("Photos", stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
                NavItem("Notifications", stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell),
            ),
            "Files",
        ) {}
    }

    private val now: Instant = Instant.now()
    private val docs = FileRoot("share/Documents", "Documents", "share", writable = true, recycle = true, recycleDays = 30, totalBytes = 8_000_000_000_000, freeBytes = 3_900_000_000_000, indexed = true)
    private val loc = Location(docs, "Privat/Urlaub 2026 am Gardasee mit der ganzen Familie")
    private val entries = listOf(
        FileEntry("Rechnungen", dir = true, modified = now.minus(20, ChronoUnit.DAYS).toString()),
        FileEntry("Ein sehr langer Ordnername, der umbrechen muss statt abgeschnitten zu werden", dir = true, modified = now.minus(2, ChronoUnit.DAYS).toString()),
        FileEntry("Mietvertrag Wohnung.pdf", size = 2_400_000, modified = "2026-11-03T10:00:00Z"),
        FileEntry("Haushaltsbuch 2026.xlsx", size = 86_000, modified = now.minus(1, ChronoUnit.DAYS).toString()),
        FileEntry("Ausweis Scan.jpg", size = 1_100_000, modified = "2026-10-12T10:00:00Z"),
        FileEntry("Notizen.md", size = 4_000, modified = now.toString()),
        FileEntry("Urlaub.mp4", size = 840_000_000, modified = now.toString()),
    )
    private val none = FolderCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {})
    private val job = Job("j1", state = "running", names = listOf("Fotos 2025"), files = 120, done = 48, bytes = 9_000_000_000, copied = 3_600_000_000, to = "share/Media", dir = "Archiv")

    @Test
    fun f20_home() = shot("120-dateien-start") {
        FilesHomeScreen(
            RootsState(listOf(docs, FileRoot("share/Media", "Media", "share", true, totalBytes = 8_000_000_000_000, freeBytes = 3_900_000_000_000), FileRoot("volume/tank", "tank", "volume", true, totalBytes = 8_000_000_000_000, freeBytes = 3_900_000_000_000), FileRoot("usb/stick", "SanDisk", "usb", false, totalBytes = 64_000_000_000, freeBytes = 12_000_000_000))),
            running = 1, finished = 4, offlineSummary = "3 Ordner, 14 Dateien · 1,2 GB",
            onRoot = {}, onTransfers = {}, onOffline = {}, onSearch = {}, onTrash = {}, onJobs = {}, bottomBar = { NavBar() },
            favorites = listOf(Favorite("share/Documents", "Steuer/2025")), jobsRunning = 1,
        )
    }

    @Test
    fun f21_list() = shot("121-ordner-liste") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries, jobs = listOf(job), clipboard = Clip(Location(docs, "Steuer"), listOf("a.pdf", "b.pdf"), move = false)), null, none, bottomBar = { NavBar() })
    }

    @Test
    fun f22_grid() = shot("122-ordner-raster") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries, view = FileView.Small, sort = SortBy.Modified, descending = true), null, none, bottomBar = { NavBar() })
    }

    @Test
    fun f22b_large() = shot("122-ordner-gross") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries, view = FileView.Large), null, none, bottomBar = { NavBar() })
    }

    @Test
    fun f23_selection() = shot("123-ordner-auswahl") {
        FolderScreen(
            FolderUi(FolderState(loc, entries), entries, selected = setOf("Mietvertrag Wohnung.pdf", "Ausweis Scan.jpg"), undo = UndoOffer("u", UndoKind.Trash, 3)),
            null, none, bottomBar = { NavBar() },
        )
    }

    @Test
    fun f24_selection_sheet() = shot("124-auswahl-aktionen") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries, selected = setOf("a", "b")), null, none, bottomBar = { NavBar() }) {
            EntrySheet(
                entries.drop(2).take(3), withTrash = true, writable = true, offline = null, officeName = null,
                onOffline = {}, onOpen = null, onOffice = {}, onOpenWith = null, onShare = {}, onDownload = {}, onCopy = {}, onCut = {},
                onRename = null, onBatchRename = {}, onEdit = null, onExtract = null, onProperties = null, onDelete = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun f25_sort() = shot("125-sortieren") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries), null, none, bottomBar = { NavBar() }) {
            SortSheet(FileView.List, SortBy.Name, false, showHidden = false, remembered = true, onChange = { _, _, _ -> }, onHidden = {}, onRemember = {}, onDismiss = {})
        }
    }

    @Test
    fun f26_add() = shot("126-hinzufuegen") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries), null, none, bottomBar = { NavBar() }) {
            AddSheet(canPaste = true, onUpload = {}, onCamera = null, onFolder = {}, onFile = {}, onUrl = {}, onPaste = {}, onDismiss = {})
        }
    }

    @Test
    fun f27_paste() = shot("127-einfuegen") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries), null, none, bottomBar = { NavBar() }) {
            PasteDialog(
                Inspection(files = 12, bytes = 48_000_000, conflicts = listOf(Conflict(path = "a.pdf")), identical = 2, different = 3),
                move = true, choice = "replace", onChoice = {}, onDo = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun f28_url() = shot("128-aus-dem-netz") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries), null, none, bottomBar = { NavBar() }) {
            UrlDialog(
                "https://example.org/downloads/handbuch-mit-sehr-langem-namen.pdf", {}, UrlProbe("handbuch.pdf", 12_000_000, "application/pdf", exists = true, free = 3_900_000_000_000),
                "handbuch.pdf", {}, checking = false, error = null, onCheck = {}, onDo = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun f29_properties() = shot("129-eigenschaften") {
        FolderScreen(FolderUi(FolderState(loc, entries), entries), null, none, bottomBar = { NavBar() }) {
            PropertiesSheet(
                "Mietvertrag Wohnung.pdf", "Documents › Privat › Mietvertrag Wohnung.pdf",
                Properties(kind = "application/pdf", size = 2_400_000, modified = "2026-11-03T10:00:00Z", changed = "2026-11-03T10:00:00Z", accessed = "2026-11-20T08:00:00Z", permissions = "-rw-rw----", uid = 1000, gid = 1000, access = io.github.ra5on.titanos.files.Access(true, true, true), sha256 = "9f2c1e7d4b0a8c6e5f3d2b1a0c9e8d7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d"),
                null, hashing = false, onChecksum = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun f30_trash() = shot("130-papierkorb") {
        TrashScreen(
            TrashUi(
                docs,
                listOf(
                    TrashEntry("Altes Angebot.docx", size = 120_000, modified = now.minus(3, ChronoUnit.DAYS).toString(), path = "alex/Angebote/Altes Angebot.docx", original = "Angebote/Altes Angebot.docx"),
                    TrashEntry("Fotos Entwurf", dir = true, modified = now.toString(), path = "alex/Fotos Entwurf", original = "Fotos Entwurf"),
                ),
                selected = setOf("alex/Fotos Entwurf"),
            ),
            onBack = {}, onToggle = {}, onRestore = {}, onDelete = {}, onEmpty = {}, onSelectAll = {},
        )
    }

    @Test
    fun f31_search() = shot("131-suche") {
        SearchScreen(
            SearchUi(
                listOf(docs, FileRoot("share/Media", "Media", "share")), docs, "Privat", query = "Miete", content = true, days = 365,
                result = SearchResult(listOf(Found("Mietvertrag Wohnung.pdf", size = 2_400_000, modified = "2026-11-03T10:00:00Z", path = "Privat/Mietvertrag Wohnung.pdf", excerpt = "… die monatliche Miete beträgt …"))),
            ),
            onChange = {}, onSearch = {}, onResult = {}, onBack = {},
        )
    }

    @Test
    fun f32_jobs() = shot("132-auftraege") {
        JobsScreen(
            listOf(
                job,
                Job("j2", operation = "download", state = "done", names = listOf("handbuch.pdf"), files = 1, done = 1, to = "share/Documents", retryable = false),
                Job("j3", move = true, state = "failed", names = listOf("Projekt"), error = "no space", errorCode = "no_space", to = "share/Media", retryable = true),
            ),
            null, onBack = {}, onCancel = {}, onPause = { _, _ -> }, onRetry = {},
        )
    }

    @Test
    fun f33_markdown() = shot("133-markdown") {
        Page(topBar = { TopBar("Notizen.md", onBack = {}) }) {
            MarkdownView(
                "# Einkauf\n\nFür das **Wochenende** am See:\n\n- Brot\n- Käse\n  - Bergkäse\n1. Zuerst zum Markt\n\n> Nicht die Kühlbox vergessen.\n\n```\nbackup --ziel share/Fotos\n```\n\nMehr unter [TitanOS](https://example.org).",
                Modifier.padding(top = 12.dp),
            )
        }
    }

    @Test
    fun f40_photo_settings() = shot("140-fotos-einstellungen-gruppen") {
        PhotosSettingsScreen(
            PhotoSettings(enabled = true, root = "share/Fotos", rootName = "Fotos", dir = "Handy Zeus", layout = LayoutChoice(structure = Structure.YearMonth, month = MonthFormat.NumberName)),
            emptyList(), {}, {}, {}, {}, {}, device = "Pixel 9", initiallyOpen = setOf("structure"),
        )
    }

    @Test
    fun f41_photo_settings_naming() = shot("141-fotos-einstellungen-namen") {
        PhotosSettingsScreen(
            PhotoSettings(enabled = true, root = "share/Fotos", rootName = "Fotos", dir = "Handy Zeus", videoOwnTarget = true, videoRoot = "share/Media", videoRootName = "Media", videoDir = "Videos Handy",
                layout = LayoutChoice(structure = Structure.Custom, pattern = "{Gerät}/{Jahr}/{Monat}", naming = io.github.ra5on.titanos.photos.Naming.Custom, namePattern = "{Jahr}-{MonatZahl}-{Tag} {Name}")),
            emptyList(), {}, {}, {}, {}, {}, device = "Pixel 9", initiallyOpen = setOf("target", "naming", "what"),
        )
    }

    @Test
    fun f42_gallery() = shot("142-fotos-auf-dem-nas") {
        val items = (0 until 14).map { i ->
            NasPhoto("share/Fotos", "Handy/2026/${if (i < 8) "11" else "10"}", PhotoEntry("IMG_$i.jpg", 2_000_000, now.minus(i * 3L, ChronoUnit.DAYS).toString(), kind = if (i % 5 == 0) "video" else "image"))
        }
        GalleryScreen(GalleryState(items, folders = 3), null, GalleryFilter.All, {}, {}, {}, {})
    }
}
