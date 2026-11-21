package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

/** Auf Fernseher oder Lautsprecher im Heimnetz abspielen (DLNA/UPnP). */
@Composable
fun BoxScope.CastSheet(location: Location, e: FileEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Renderer>?>(null) }
    var playing by remember { mutableStateOf<Renderer?>(null) }
    var failed by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        devices = null
        devices = runCatching { Dlna.discover(context) }.getOrDefault(emptyList())
    }
    // Ohne laufende Wiedergabe gibt der Weiterreicher nichts mehr frei.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (playing == null) MediaRelay.stop() } }
    val c = Titan.colors
    Sheet(onDismiss) {
        Text(stringResource(R.string.cast_title), style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 6.dp))
        Text(e.name, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(bottom = 12.dp))
        if (failed) Problem(stringResource(R.string.cast_failed), Modifier.padding(bottom = 10.dp))
        val list = devices
        when {
            list == null -> Text(stringResource(R.string.cast_searching), style = TitanType.body, color = c.muted)
            list.isEmpty() -> Note(stringResource(R.string.cast_none))
            else -> Card(background = c.bg) {
                list.forEachIndexed { i, r ->
                    val on = playing == r
                    ListRow(
                        r.name,
                        sub = if (on) stringResource(R.string.cast_playing) else r.location.substringAfter("//").substringBefore('/'),
                        icon = { RowIcon(TitanIcons.monitor) },
                        divider = i > 0,
                        onClick = {
                            failed = false
                            scope.launch {
                                val url = MediaRelay.share(location.root.id, location.full(e.name), NasDocumentsProvider.mimeOf(e.name), e.size)
                                val ok = url != null && Dlna.play(r, url, e.name, NasDocumentsProvider.mimeOf(e.name))
                                if (ok) playing = r else failed = true
                            }
                        },
                    )
                }
            }
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.cast_search_again), onClick = { round++ })
            playing?.let { r ->
                Chip(stringResource(R.string.jobs_pause), onClick = { scope.launch { Dlna.pause(r) } })
                Chip(stringResource(R.string.jobs_resume), onClick = { scope.launch { Dlna.resume(r) } })
                Chip(stringResource(R.string.cast_stop), onClick = { scope.launch { Dlna.stop(r); MediaRelay.stop(); playing = null } })
            }
        }
        Note(stringResource(R.string.cast_note), Modifier.padding(top = 12.dp))
    }
}
