package io.github.ra5on.titanos.settings.pages.backups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons

/** Sicherungen und ihre Unterseiten ([id]: `backups`, `backups/data`, …). */
@Composable
fun BackupsPage(a: PageArgs, id: String) {
    when (id) {
        "backups/data" -> DataBackups(a, restoreOnly = false)
        "backups/applications" -> ApplicationsPage(a)
        "backups/configuration" -> ConfigurationPage(a)
        "backups/coverage" -> CoveragePage(a)
        "backups/restore" -> RestorePage(a)
        else -> Overview(a)
    }
}

/**
 * Öffnet eine Unterseite an einer Stelle (Anker im Web wie
 * `/settings/backups/data#backup-targets`): die Suche der Systemsteuerung
 * rückt die Gruppe oder Zeile mit diesem Titel ins Bild.
 */
internal fun PageArgs.openAt(page: String, title: String) = nav.openPath("/settings/$page", listOf(title))

/** Öffnet die Datensicherung mit diesem Auftrag gewählt (`#backup-<id>`). */
internal fun PageArgs.openJob(id: String) {
    openPage("backups/data")
    tab = "job:$id"
}

/** Der Text eines Fehlers des NAS in eigenen Worten (hinter „Einzelheit“). */
internal fun apiMessage(e: Throwable): String {
    val api = e as? ApiException ?: return ""
    return api.message.orEmpty().substringAfter("${api.code}: ", "")
}

/** Fehler wie `operationError` im Web: „Der Vorgang …“ und die Meldung. */
internal fun Tr.operationError(e: Throwable): String = t("ops.failed") + " " + errorOf(e)

/** Text in Akzentfarbe, der woandershin führt (a im Web). */
@Composable
internal fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TitanType.body,
        color = Titan.colors.accent,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 32.dp).padding(vertical = 6.dp),
    )
}

/** Ein Wert rechts in einer Zeile (strong im Web). */
@Composable
internal fun Value(text: String) = Text(text, style = TitanType.rowSub.copy(fontWeight = FontWeight.Medium), color = Titan.colors.text)

/**
 * Übersicht (routes/settings/backups/+page.svelte): Stand der Daten- und
 * Konfigurationssicherung, 3-2-1-Regel und alle Teile als Zeilen. Die Seite
 * fragt nie in Abständen (sonst wachen schlafende Platten auf).
 */
@Composable
private fun Overview(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val jobs = rememberLoad { BkApi.overview(env.api) }
    val config = rememberLoad { BkApi.config(env.api) }
    val loading = jobs.loading || config.loading
    val reload = { if (!loading) { jobs.reload(); config.reload() } }
    val views = jobs.value?.a("jobs")?.objects()
    val backup = config.value?.o("backup")
    fun date(v: String?) = if (v != null) Fmt.dateTime(tr, v) else tr.t("safeguard.none")

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        SmallButton(t(if (loading) "common.loading" else "safeguard.refresh"), reload, enabled = !loading)
    }
    PageIntro(t("safeguard.intro"))

    val unknown = views?.filter { Bk.unknown(it) }.orEmpty()
    val failures = views?.filter { Bk.failed(it.o("state")) }.orEmpty()
    val warnings = views?.filter { Bk.warned(it.o("state")) }.orEmpty()
    val lastData = views?.let { v -> Bk.latest(v.map { it.o("state").str("lastOK") }) }
    val lastConfig = backup?.let { b -> Bk.latest(b.a("backups").objects().map { it.str("time") }) }
    val next = if (views != null && backup != null) {
        Bk.earliest(views.filter { it.o("job").bool("enabled") }.map { it.str("next") } + if (backup.o("settings").bool("enabled")) listOf(backup.str("next")) else emptyList())
    } else null
    val waiting = tr.t(if (loading) "common.loading" else "safeguard.unknown")

    SettingGroup(t("safeguard.state")) {
        SettingRow(t("safeguard.lastData"), icon = WebIcons["archive"], id = "backup-last-data", control = {
            Value(if (unknown.isNotEmpty()) tr.t("safeguard.unknown") else if (views != null) date(lastData) else waiting)
        })
        SettingRow(t("safeguard.lastConfig"), icon = WebIcons["gear"], id = "backup-last-config", control = {
            Value(if (backup != null) date(lastConfig) else waiting)
        })
        SettingRow(t("safeguard.next"), icon = WebIcons["clock"], id = "backup-next", control = {
            Value(if (views == null || backup == null) waiting else if (next != null) Fmt.dateTime(tr, next) else tr.t("safeguard.noSchedule"))
        })
    }
    if (jobs.error != null || config.error != null) {
        Notice(Tone.Error) {
            jobs.error?.let { NoticeText("${tr.t("safeguard.data")}: ${tr.errorOf(it)}", Tone.Error) }
            config.error?.let { NoticeText("${tr.t("safeguard.config")}: ${tr.errorOf(it)}", Tone.Error) }
            SmallButton(t("ui.retry"), reload, enabled = !loading)
        }
    }
    if (failures.isNotEmpty()) {
        Notice(Tone.Error) {
            NoticeText(t("safeguard.dataErrors", "count" to failures.size), Tone.Error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (v in failures) { val id = v.o("job").str("id"); LinkText(id, { a.openJob(id) }) }
            }
        }
    }
    for (v in unknown) {
        val id = v.o("job").str("id")
        Notice(Tone.Warn) { LinkText(id, { a.openJob(id) }); NoticeText(t("coverage.stateUnknown"), Tone.Warn) }
    }
    for (v in warnings) {
        val id = v.o("job").str("id")
        Notice(Tone.Warn) { LinkText(id, { a.openJob(id) }); NoticeText(Bk.notesLine(tr, v.o("state")), Tone.Warn) }
    }
    if (backup != null && backup.str("lastError").isNotEmpty()) {
        Notice(Tone.Error) {
            LinkText(t("safeguard.config"), { a.openPage("backups/configuration") })
            NoticeText(t("sysdata.config.failed"), Tone.Error)
            BackendDetail(backup.str("lastError"), Tone.Error)
        }
    }
    if (backup != null && !backup.o("settings").bool("enabled")) NoticeLine(t("safeguard.configOff"), Tone.Warn)
    else if (backup != null && backup.str("target").isEmpty()) NoticeLine(t("stick.noVolume"), Tone.Warn)

    if (views != null) Rule321Group(a, views)

    SettingGroup(t("safeguard.data"), desc = t("safeguard.dataHint"), tools = { SmallButton(t("safeguard.manage"), { a.openPage("backups/data") }) }) {
        SettingRow(t("safeguard.jobs"), icon = WebIcons["list"], onClick = { a.openPage("backups/data") })
        SettingRow(t("safeguard.targets"), icon = WebIcons["disk"], onClick = { a.openAt("backups/data", tr.t("safeguard.targets")) })
        SettingRow(t("safeguard.history"), icon = WebIcons["activity"], id = "backup-data-history", onClick = { a.openAt("backups/data", tr.t("safeguard.history")) })
        SettingRow(t("coverage.title"), icon = WebIcons["shield"], onClick = { a.openPage("backups/coverage") })
        SettingRow(t("applications.title"), icon = WebIcons["box"], onClick = { a.openPage("backups/applications") })
    }
    SettingGroup(t("safeguard.config"), desc = t("safeguard.configHint"), tools = { SmallButton(t("stick.title"), { a.openPage("backups/configuration") }) }) {
        SettingRow(t("safeguard.automatic"), icon = WebIcons["clock"], onClick = { a.openAt("backups/configuration", tr.t("stick.settings")) })
        SettingRow(t("safeguard.download"), icon = WebIcons["download"], onClick = { a.openAt("backups/configuration", tr.t("stick.backups")) })
        SettingRow(t("safeguard.history"), icon = WebIcons["activity"], id = "backup-config-history", onClick = { a.openAt("backups/configuration", tr.t("stick.state")) })
    }
    SettingGroup(t("safeguard.restore"), desc = t("safeguard.recoveryHint"), tools = { SmallButton(t("safeguard.openRestore"), { a.openPage("backups/restore") }) }) {
        SettingRow(t("safeguard.filesRestore"), icon = WebIcons["folder"], onClick = { a.openAt("backups/restore", tr.t("safeguard.filesRestore")) })
        SettingRow(t("safeguard.configRestore"), icon = WebIcons["undo"], onClick = { a.openAt("backups/restore", tr.t("safeguard.configRestore")) })
        SettingRow(t("stick.replace"), icon = WebIcons["key"], onClick = { a.openAt("backups/restore", tr.t("stick.replace")) })
    }
}

/** Die 3-2-1-Regel (Rule321.svelte): drei Zeilen, jede sagt in Worten, ob sie erfüllt ist. */
@Composable
private fun Rule321Group(a: PageArgs, views: List<kotlinx.serialization.json.JsonObject>) {
    val tr = LocalTr.current
    val rule = Bk.rule321(views)
    val all = rule.three && rule.two && rule.one
    @Composable
    fun chip(ok: Boolean, yes: String, no: String) = StatusChip(t(if (ok) yes else no), if (ok) Tone.Ok else Tone.Warn)
    SettingGroup(t("rule321.title"), desc = t("rule321.intro"), tools = { chip(all, "rule321.met", "rule321.notMet") }) {
        val rows = listOf(
            Triple(rule.three, tr.t("rule321.three"), tr.t("rule321.threeDetail", "count" to rule.copies, "backups" to rule.copies - 1)),
            Triple(rule.two, tr.t("rule321.two"), tr.t("rule321.twoDetail", "count" to rule.media.size, "media" to rule.media.joinToString(", ") { tr.t("rule321.medium.$it") })),
            Triple(rule.one, tr.t("rule321.one"), tr.t("rule321.oneDetail", "count" to rule.offsite)),
        )
        for ((ok, title, detail) in rows) {
            SettingRow(title, desc = detail, icon = WebIcons[if (ok) "check" else "info"], control = { chip(ok, "rule321.yes", "rule321.no") })
        }
        Muted(t("rule321.note"), Modifier.padding(top = 4.dp))
        LinkText(t("coverage.title"), { a.openPage("backups/coverage") })
    }
}
