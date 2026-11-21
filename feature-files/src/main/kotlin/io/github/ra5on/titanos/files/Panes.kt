package io.github.ra5on.titanos.files

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons

/**
 * Seitenleiste ab „Wide“ (wie Places im Web am PC): Favoriten, Ablagen und
 * die Orte am NAS und auf dem Handy; die offene Ablage ist markiert.
 */
@Composable
fun FilesSidebar(
    roots: List<FileRoot>,
    favorites: List<Favorite>,
    current: String?,
    onRoot: (FileRoot) -> Unit,
    onFavorite: (Favorite) -> Unit,
    onTrash: () -> Unit,
    onJobs: () -> Unit,
    onTransfers: () -> Unit,
    onOffline: () -> Unit,
    onSearch: () -> Unit,
) {
    val c = Titan.colors
    Column(
        Modifier.width(280.dp).fillMaxHeight().background(c.header).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
    ) {
        SectionHeader(stringResource(R.string.files_title), top = 16.dp)
        Card {
            ListRow(stringResource(R.string.search_title), icon = { RowIcon(TitanIcons.search) }, onClick = onSearch)
        }
        if (favorites.isNotEmpty()) {
            SectionHeader(stringResource(R.string.files_favorites))
            Card {
                favorites.forEachIndexed { i, f ->
                    val root = roots.firstOrNull { it.id == f.root }
                    ListRow(
                        f.label ?: f.path.substringAfterLast('/').ifEmpty { root?.name ?: f.root },
                        icon = { RowIcon(TitanIcons.pin) },
                        divider = i > 0,
                        onClick = { onFavorite(f) },
                    )
                }
            }
        }
        SectionHeader(stringResource(R.string.files_shares))
        Card {
            roots.forEachIndexed { i, r ->
                val on = r.id == current
                Box(Modifier.background(if (on) c.accent.copy(alpha = .18f) else c.surface)) {
                    ListRow(
                        r.name,
                        icon = { if (r.kind == "share" || r.kind.isEmpty()) AppIcon("shares", "yellow", 30.dp) else RowIcon(TitanIcons.hdd) },
                        divider = i > 0,
                        onClick = { onRoot(r) },
                    )
                }
            }
        }
        SectionHeader(stringResource(R.string.files_on_nas))
        Card {
            ListRow(stringResource(R.string.files_trash), icon = { RowIcon(TitanIcons.trash) }, onClick = onTrash)
            ListRow(stringResource(R.string.jobs_title), icon = { RowIcon(TitanIcons.sync) }, divider = true, onClick = onJobs)
        }
        SectionHeader(stringResource(R.string.files_on_phone))
        Card {
            ListRow(stringResource(R.string.files_offline), icon = { RowIcon(TitanIcons.offline) }, onClick = onOffline)
            ListRow(stringResource(R.string.files_transfers), icon = { RowIcon(TitanIcons.upload) }, divider = true, onClick = onTransfers)
        }
        Divider(Modifier.padding(top = 12.dp))
    }
}

/** Was eine Taste im Ordner bedeutet (wie keyboard.ts im Web). */
enum class FolderKey { Copy, Cut, Paste, Delete, Rename, Search, NewFolder, SelectAll, Up, Down, Open, Preview, Back, Escape, Menu, Refresh, Home, End }

fun folderKey(e: KeyEvent): FolderKey? {
    if (e.type != KeyEventType.KeyDown) return null
    val mod = e.isCtrlPressed || e.isMetaPressed
    return when {
        mod && e.key == Key.C -> FolderKey.Copy
        mod && e.key == Key.X -> FolderKey.Cut
        mod && e.key == Key.V -> FolderKey.Paste
        mod && e.key == Key.A -> FolderKey.SelectAll
        mod && e.key == Key.F -> FolderKey.Search
        mod && e.isShiftPressed && e.key == Key.N -> FolderKey.NewFolder
        mod && e.key == Key.R -> FolderKey.Refresh
        e.key == Key.F5 -> FolderKey.Refresh
        e.key == Key.Delete || (e.isMetaPressed && e.key == Key.Backspace) -> FolderKey.Delete
        e.key == Key.F2 -> FolderKey.Rename
        e.key == Key.DirectionUp && !e.isAltPressed -> FolderKey.Up
        e.key == Key.DirectionDown -> FolderKey.Down
        e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> null
        e.key == Key.Enter || e.key == Key.NumPadEnter -> FolderKey.Open
        e.key == Key.Spacebar -> FolderKey.Preview
        e.key == Key.Backspace || (e.isAltPressed && e.key == Key.DirectionUp) -> FolderKey.Back
        e.key == Key.Escape -> FolderKey.Escape
        e.key == Key.Menu || (e.isShiftPressed && e.key == Key.F10) -> FolderKey.Menu
        e.key == Key.MoveHome -> FolderKey.Home
        e.key == Key.MoveEnd -> FolderKey.End
        else -> null
    }
}

/** Ordner oder Datei in einem eigenen Fenster (neben diesem, wenn Platz ist). */
object FilesWindow {
    const val EXTRA_ROOT = "titanos.files.root"
    const val EXTRA_PATH = "titanos.files.path"
    const val EXTRA_NAME = "titanos.files.name"

    fun open(context: Context, location: Location, name: String? = null) {
        val i = Intent(context, FilesWindowActivity::class.java)
            .putExtra(EXTRA_ROOT, location.root.id)
            .putExtra(EXTRA_PATH, location.path)
            .apply { if (name != null) putExtra(EXTRA_NAME, name) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
