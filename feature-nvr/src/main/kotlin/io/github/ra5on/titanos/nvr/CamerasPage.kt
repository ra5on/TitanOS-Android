package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.ListDetail
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun CamerasPage(model: NvrModel, me: String, nav: NvrNav) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val tick = remember { System.currentTimeMillis() }
    val removeConfirm = t("nvr.cameras.remove")
    val removeText = t("nvr.cameras.removeText") + "\n" + t("nvr.cameras.removeRights")
    val purgeWord = t("nvr.removed.purge")
    val purgeText = listOf(t("nvr.removed.purgeText"), t("nvr.removed.purgeExports"), t("nvr.removed.purgeRestart")).joinToString("\n")

    fun act(id: String, block: suspend () -> Unit) {
        busy = id
        error = null
        ui.run(onError = { busy = ""; error = it }) {
            block()
            model.refresh()
            busy = ""
        }
    }
    val actions = CameraActions(
        onSwitch = { c, on -> act(c.id) { model.api.changeCamera(c.id, buildJsonObject { put("enabled", on) }) } },
        onMove = { index, by ->
            val list = model.cameras.toMutableList()
            val to = index + by
            if (to in list.indices) {
                val x = list[index]; list[index] = list[to]; list[to] = x
                act(list[to].id) {
                    // Jede Kamera bekommt ihren Platz: gleiche oder fehlende Reihenfolgen ordnen sich.
                    list.forEachIndexed { i, cam -> if (cam.order != i + 1) model.api.changeCamera(cam.id, buildJsonObject { put("order", i + 1) }) }
                }
            }
        },
        onEdit = { nav.editCamera(it.id) },
        onRemove = { c ->
            ui.confirm(Ask(context.tx("nvr.cameras.removeTitle", "name" to c.name), removeText, removeConfirm) { act(c.id) { model.api.removeCamera(c.id) } })
        },
        onShowRecordings = { nav.openRecordings(it.id) },
        onPurge = { c ->
            ui.confirm(Ask(context.tx("nvr.removed.purgeTitle", "name" to c.name), purgeText, purgeWord) { act(c.id) { model.api.purgeRecordings(c.id) } })
        },
        onAdd = { nav.addCamera() },
    )
    val editing = nav.edit?.let { id -> model.cameras.firstOrNull { it.id == id } }
    ListDetail(
        detailOpen = nav.adding || nav.edit != null,
        onCloseDetail = { nav.adding = false; nav.edit = null },
        listWidth = 460.dp,
        list = { CamerasScreen(model.cameras, model.removed, model.running, busy, error, tick, actions) },
        detail = {
            when {
                nav.adding -> CameraForm(null, model.status?.hardware, model.objects, model.cameras.map { it.name }, me, model.api,
                    onCancel = { nav.adding = false }, onSaved = { nav.adding = false; ui.run { model.refresh() } })
                nav.edit != null && editing != null -> androidx.compose.runtime.key(editing.id) {
                    CameraForm(editing, model.status?.hardware, model.objects, model.cameras.filter { it.id != editing.id }.map { it.name }, me, model.api,
                        onCancel = { nav.edit = null }, onSaved = { nav.edit = null; ui.run { model.refresh() } })
                }
                nav.edit != null -> Column(Modifier.padding(16.dp)) {
                    Problem(t("error.nvr_camera_not_found"))
                    TitanButton(t("nvr.menu.cameras"), { nav.edit = null })
                }
                else -> EmptyState(t("nvr.menu.cameras"), t("nvr.add.intro"))
            }
        },
    )
}

class CameraActions(
    val onSwitch: (Camera, Boolean) -> Unit = { _, _ -> },
    val onMove: (Int, Int) -> Unit = { _, _ -> },
    val onEdit: (Camera) -> Unit = {},
    val onRemove: (Camera) -> Unit = {},
    val onShowRecordings: (Camera) -> Unit = {},
    val onPurge: (Camera) -> Unit = {},
    val onAdd: () -> Unit = {},
)

/** Die Kameras (cameras/+page.svelte): je eine Zeile mit Zustand, Ein/Aus, Reihenfolge, Ändern, Entfernen. */
@Composable
fun CamerasScreen(cameras: List<Camera>, removed: List<Camera>, running: Boolean, busy: String, error: Throwable?, tick: Long, actions: CameraActions) {
    val ui = LocalComputeUi.current
    val loc = locale()
    val onWord = androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.compute.R.string.compute_on)
    val offWord = androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.compute.R.string.compute_off)
    ScrollPage {
        TitanButton(t("nvr.add.title"), actions.onAdd, Modifier.padding(top = 8.dp), kind = ButtonKind.Primary, icon = TitanIcons.plus)
        ErrorLine(error)
        if (cameras.isEmpty()) {
            EmptyState(t("nvr.live.noneTitle"), t("nvr.live.noneAdmin"))
        } else {
            val many = cameras.count { it.enabled } > 4
            Group(if (cameras.size == 1) t("nvr.cameras.countOne") else t("nvr.cameras.count", "count" to cameras.size), if (many) t("nvr.cameras.many") else null) {
                cameras.forEachIndexed { i, c ->
                    val cond = c.condition(running)
                    val summary = buildList {
                        c.source?.let { add(it.host) }
                        c.detect?.let { det ->
                            add(
                                if (det.enabled) (groupsOf(det.objectList).map { g -> t_g("nvr.detect.$g") } + listOfNotNull(if (det.plates) t_g("nvr.detect.plates") else null, if (det.faces) t_g("nvr.detect.faces") else null)).joinToString(", ")
                                else t_g("nvr.detect.nothing"),
                            )
                        }
                        c.record?.let { add(t_g("nvr.record.short.${it.mode}")) }
                    }.joinToString(" · ")
                    val menu = listOf(
                        MenuEntry(t("nvr.cameras.edit"), TitanIcons.edit) { actions.onEdit(c) },
                        MenuEntry(t("nvr.cameras.up"), NvrIcons.up, enabled = i > 0 && busy.isEmpty()) { actions.onMove(i, -1) },
                        MenuEntry(t("nvr.cameras.down"), NvrIcons.downArrow, enabled = i < cameras.size - 1 && busy.isEmpty()) { actions.onMove(i, 1) },
                        MenuEntry(t("nvr.cameras.remove"), TitanIcons.trash, danger = true) { actions.onRemove(c) },
                    )
                    Box(Modifier.onSecondaryClick { ui.menu(c.name, menu) }) {
                        Row2(
                            c.name,
                            divider = i > 0,
                            desc = summary,
                            descContent = {
                                StateMark(stateText(c, running), when (cond) { Condition.Online -> StateKind.Ok; Condition.Offline -> StateKind.Error; Condition.Off -> StateKind.Off; else -> StateKind.Info })
                            },
                        ) {
                            if (c.enabled && running) NvrPicture(NvrApi.snapshotPath(c.id, 120, tick), null, Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
                            Toggle(c.enabled, if (busy == c.id) null else { on -> actions.onSwitch(c, on) }, "$onWord, " + t("nvr.cameras.enable", "name" to c.name), "$offWord, " + t("nvr.cameras.enable", "name" to c.name), busy != c.id)
                            io.github.ra5on.titanos.ui.components.IconButton(NvrIcons.up, t("nvr.arrange.up", "name" to c.name), { if (i > 0 && busy.isEmpty()) actions.onMove(i, -1) })
                            io.github.ra5on.titanos.ui.components.IconButton(NvrIcons.downArrow, t("nvr.arrange.down", "name" to c.name), { if (i < cameras.size - 1 && busy.isEmpty()) actions.onMove(i, 1) })
                            TitanButton(t("nvr.cameras.edit"), { actions.onEdit(c) }, icon = TitanIcons.edit)
                            TitanButton(t("nvr.cameras.remove"), { actions.onRemove(c) }, kind = ButtonKind.Danger, enabled = busy != c.id, icon = TitanIcons.trash)
                        }
                    }
                }
            }
        }
        if (removed.isNotEmpty()) {
            Group(t("nvr.removed.group"), t("nvr.removed.groupHint")) {
                removed.forEachIndexed { i, c ->
                    val until = unixOf(c.expiresAt)
                    val since = unixOf(c.deletedAt)
                    Row2(
                        c.name,
                        NvrIcons.camera,
                        divider = i > 0,
                        summary = if (c.purging) t("nvr.removed.purging") else if (until != null) t("nvr.removed.until", "date" to dateText(until, loc)) else t("nvr.removed.untilUnknown"),
                        descContent = {
                            if (since != null) Sub(t("nvr.removed.since", "date" to dateText(since, loc)))
                            if (c.purging) Sub(t("nvr.removed.purgingHint")) else if (!running) Sub(t("nvr.removed.purgeNeedsRunning"), warn = true)
                        },
                    ) {
                        TitanButton(t("nvr.removed.show"), { actions.onShowRecordings(c) }, icon = TitanIcons.play)
                        TitanButton(t("nvr.removed.purge"), { actions.onPurge(c) }, kind = ButtonKind.Danger, enabled = running && busy != c.id && !c.purging, icon = TitanIcons.trash)
                    }
                }
            }
        }
    }
}

@Composable
private fun t_g(key: String) = t(key)
