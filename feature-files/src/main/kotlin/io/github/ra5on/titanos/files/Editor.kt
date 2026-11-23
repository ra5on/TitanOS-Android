package io.github.ra5on.titanos.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Zustand des Editors (TextEditor.svelte): Inhalt, Version am NAS, Entwurf. */
data class EditorState(
    val loading: Boolean = true,
    val original: String = "",
    val draft: String = "",
    val version: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val conflict: Boolean = false,
    val error: FilesError? = null,
    val previous: String? = null,
    val previousMissing: Boolean = false,
) {
    val dirty: Boolean get() = draft != original
}

/**
 * Texteditor wie im Web: lädt über `files/text` (höchstens 512 KiB UTF-8),
 * speichert mit der Version (409 text_conflict: am NAS geändert, der Entwurf
 * bleibt), die vorige Fassung (`.prev`) ansehen und zurückholen.
 */
@Composable
fun EditorScreen(api: FilesApi, location: Location, name: String, onClose: () -> Unit) {
    val path = location.full(name)
    var s by remember(path) { mutableStateOf(EditorState()) }
    var askClose by remember { mutableStateOf(false) }
    var showPrevious by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun errorOf(e: Exception): FilesError = when {
        e is ApiException && e.code == "text_conflict" -> FilesError.Other("text_conflict")
        e is ApiException && e.code == "invalid_json" -> FilesError.Other("invalid_json")
        e is ApiException && e.code == "not_text" -> FilesError.NotText
        e is ApiException && e.code == "read_only_share" -> FilesError.ReadOnly
        e is ApiException && e.code == "invalid_path" -> FilesError.InvalidName
        e is ApiException -> FilesError.Other("${e.status} ${e.code}")
        else -> FilesError.Offline
    }
    LaunchedEffect(path) {
        s = try {
            val doc = api.text(location.root.id, path)
            EditorState(loading = false, original = doc.content, draft = doc.content, version = doc.version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EditorState(loading = false, error = errorOf(e))
        }
    }
    fun save(force: Boolean) {
        scope.launch {
            s = s.copy(saving = true, error = null, saved = false)
            try {
                val version = if (force) api.text(location.root.id, path).version else s.version
                val doc = api.saveText(location.root.id, path, s.draft, version)
                s = s.copy(saving = false, original = doc.content, draft = doc.content, version = doc.version, saved = true, conflict = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val conflict = e is ApiException && e.code == "text_conflict"
                s = s.copy(saving = false, conflict = conflict, error = if (conflict) null else errorOf(e))
            }
        }
    }
    fun loadPrevious() {
        scope.launch {
            s = try {
                s.copy(previous = api.previousText(location.root.id, path).content, previousMissing = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.copy(previous = null, previousMissing = true)
            }
            showPrevious = true
        }
    }
    val close = { if (s.dirty) askClose = true else onClose() }
    BackHandler(onBack = close)
    val c = Titan.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.statusBarsPadding()) {
                TopBar(
                    name,
                    onBack = close,
                    backLabel = stringResource(UiR.string.ui_back),
                    actions = {
                        IconButton(TitanIcons.restart, stringResource(R.string.edit_previous), { loadPrevious() })
                        IconButton(TitanIcons.check, stringResource(R.string.edit_save), { if (!s.saving && !s.loading) save(false) }, tint = if (s.dirty) c.accent else c.muted)
                    },
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    Chip(
                        stringResource(
                            when {
                                s.loading -> UiR.string.ui_loading
                                s.saving -> R.string.edit_saving
                                s.dirty -> R.string.edit_unsaved
                                s.saved -> R.string.edit_saved
                                else -> R.string.edit_unchanged
                            },
                        ),
                    )
                }
                s.error?.let { e ->
                    Problem(
                        when (e) {
                            is FilesError.Other -> if (e.detail == "invalid_json") stringResource(R.string.edit_invalid_json) else errorText(e)
                            else -> errorText(e)
                        },
                        Modifier.padding(bottom = 10.dp),
                    )
                }
                if (!s.loading && s.error?.let { it is FilesError.NotText } != true) {
                    val shape = RoundedCornerShape(12.dp)
                    BasicTextField(
                        value = s.draft,
                        onValueChange = { s = s.copy(draft = it, saved = false) },
                        textStyle = TitanType.small.copy(fontFamily = FontFamily.Monospace, color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(12.dp),
                    )
                    Note(stringResource(R.string.edit_note), Modifier.padding(top = 12.dp))
                }
                Box(Modifier.navigationBarsPadding())
            }
        }
        if (s.conflict) {
            ConfirmDialog(
                title = stringResource(R.string.edit_conflict_title),
                text = stringResource(R.string.edit_conflict_text),
                actions = listOf(
                    DialogAction(stringResource(R.string.edit_overwrite), danger = true) { s = s.copy(conflict = false); save(true) },
                    DialogAction(stringResource(R.string.edit_keep_draft)) { s = s.copy(conflict = false) },
                ),
                onDismiss = { s = s.copy(conflict = false) },
            )
        }
        if (askClose) {
            ConfirmDialog(
                title = stringResource(R.string.edit_discard_title),
                text = stringResource(R.string.edit_discard_text),
                actions = listOf(
                    DialogAction(stringResource(R.string.edit_discard), danger = true) { askClose = false; onClose() },
                    DialogAction(stringResource(R.string.edit_continue)) { askClose = false },
                ),
                onDismiss = { askClose = false },
            )
        }
        if (showPrevious) {
            ConfirmDialog(
                title = stringResource(R.string.edit_previous),
                text = if (s.previousMissing) stringResource(R.string.edit_previous_missing) else stringResource(R.string.edit_previous_text),
                actions = buildList {
                    if (s.previous != null) add(DialogAction(stringResource(R.string.edit_previous_restore)) { s = s.copy(draft = s.previous!!); showPrevious = false })
                    add(DialogAction(stringResource(UiR.string.ui_close)) { showPrevious = false })
                },
                onDismiss = { showPrevious = false },
            ) {
                s.previous?.let {
                    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                        Text(it, style = TitanType.small.copy(fontFamily = FontFamily.Monospace), color = c.text)
                    }
                }
            }
        }
    }
}
