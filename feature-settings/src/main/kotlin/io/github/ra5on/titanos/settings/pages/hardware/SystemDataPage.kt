package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.longOrNull
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

private val SYSDATA_ICONS = mapOf("config" to "refresh", "logs" to "fileText", "activity" to "activity", "notify" to "bell", "smart" to "shield", "monitoring" to "monitor")
private val SYSDATA_LOGS = listOf("logs", "activity", "notify", "smart", "monitoring")

/** Die Volumes einer Auswahl: eingebundene und das gespeicherte, auch wenn es fehlt. */
private fun offeredVolumes(state: JsonObject, current: String) = state.a("volumes").objects().filter { it.bool("mounted") || it.str("name") == current }

/**
 * Systemdaten (routes/settings/systemdata): wo TitanOS seine eigenen Daten
 * ablegt – ein Volume für alles, die Sicherung der Einstellungen und je
 * Protokoll und Verlauf ein eigenes Volume. Eine Wahl wird sofort
 * gespeichert (`PUT /system/data` nur mit dem Geänderten, wie im Web); die
 * Daten ziehen im Hintergrund um, die Seite fragt dann alle 3 s nach
 * (höchstens 40 Mal).
 */
@Composable
fun SystemDataPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val run = rememberRunner()
    val savedText = t("ui.saved")
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var config by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var polls by remember { mutableIntStateOf(0) }
    var loadRound by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadRound) {
        error = ""
        try {
            val state = env.api.obj("system/data")
            config = runCatching { env.api.obj("config-backup") }.getOrNull()
            data = state
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    // Während ein Ordner umzieht, fragt die Seite nach, damit Größen und „wird verschoben“ folgen.
    LaunchedEffect(data) {
        val state = data ?: return@LaunchedEffect
        val waiting = state.a("functions").objects().any { it.bool("moving") || (it.str("volume").isNotEmpty() && it.longOrNull("sizeBytes") == null) }
        if (waiting && polls < 40) {
            delay(3000)
            polls++
            runCatching { data = env.api.obj("system/data") }
        }
    }

    fun store(body: JsonObject) {
        run.run(guard = GuardAction.Settings, ok = savedText) {
            polls = 0
            try {
                data = env.api.put("system/data", body) as? JsonObject ?: data
                // Die Kopien von /config nennen ihr Ziel selbst.
                if (body.containsKey("volume") || body.o("functions").containsKey("config")) {
                    config = runCatching { env.api.obj("config-backup") }.getOrNull() ?: config
                }
            } catch (e: Exception) {
                runCatching { data = env.api.obj("system/data") }
                throw e
            }
        }
    }

    fun setPace(pace: String) {
        val cfg = config ?: return
        run.run(guard = GuardAction.Settings, ok = savedText) {
            config = env.api.put("config-backup", cfg.o("backup").o("settings").with("pace" to pace)) as? JsonObject ?: cfg
        }
    }

    PageIntro(t("sysdata.intro"))
    if (error.isNotEmpty()) Banner(error, tone = Tone.Error, action = if (data == null) t("ui.retry") else null, onAction = if (data == null) ({ loadRound++ }) else null)
    val state = data
    if (state == null) {
        if (error.isEmpty()) LoadingLine(t("common.loading"))
        return
    }
    val functions = state.a("functions").objects()
    val volumes = state.a("volumes").objects()
    val resolved = state.str("resolved")
    val common = state.str("volume")
    val sleepKnown = state.bool("sleepKnown")
    val sleeps = volumes.filter { it.bool("sleeps") }.map { it.str("name") }.toSet()

    fun volumeLabel(v: JsonObject): String {
        val note = when {
            !v.bool("mounted") -> tr.t("sysdata.volume.unmounted")
            !sleepKnown -> ""
            else -> tr.t(if (v.bool("sleeps")) "sysdata.volume.sleeps" else "sysdata.volume.awake")
        }
        return if (note.isNotEmpty()) "${v.str("name")} · $note" else v.str("name")
    }

    @Composable
    fun Choice(f: JsonObject) {
        val id = f.str("id")
        val choice = f.str("choice")
        val options = listOf("" to if (resolved.isNotEmpty()) t("sysdata.inherit", "volume" to resolved) else t("sysdata.inheritNone")) +
            offeredVolumes(state, choice).map { it.str("name") to volumeLabel(it) }
        Select(choice, options, { v -> if (v != choice) store(jobj("functions" to mapOf(id to v))) }, label = t("sysdata.fn.$id"), enabled = !run.running)
    }

    SettingGroup(t("sysdata.group.store")) {
        SettingRow(
            t("sysdata.common"),
            desc = t("sysdata.common.desc"),
            icon = WebIcons["disk"],
            control = {
                val options = listOf("" to if (resolved.isNotEmpty() && common.isEmpty()) t("sysdata.automaticNow", "volume" to resolved) else t("sysdata.automatic")) +
                    offeredVolumes(state, common).map { it.str("name") to volumeLabel(it) }
                Select(common, options, { v -> if (v != common) store(jobj("volume" to v)) }, label = t("sysdata.common"), enabled = !run.running)
            },
        )
        when {
            volumes.isEmpty() || (resolved.isEmpty() && common.isEmpty()) -> Banner(t("sysdata.noVolume"), tone = Tone.Warn)
            resolved.isEmpty() -> Banner(t("sysdata.unmounted", "volume" to common), tone = Tone.Warn)
            !sleepKnown -> Unit
            resolved in sleeps -> Banner(t("sysdata.hint.warn", "volume" to resolved), tone = Tone.Warn)
            else -> {
                val used = functions.map { it.str("volume") }.toSet()
                val spared = sleeps.filter { it !in used }.sorted()
                val extra = when {
                    spared.size == 1 -> " " + t("sysdata.hint.spared", "volume" to spared[0])
                    spared.size > 1 -> " " + t("sysdata.hint.sparedMany", "volumes" to spared.joinToString(", ") { "„$it“" })
                    else -> ""
                }
                Banner(t("sysdata.hint.ok") + extra, tone = Tone.Ok)
            }
        }
        val asleep = if (sleepKnown) functions.filter { it.str("choice").isNotEmpty() && it.str("choice") in sleeps } else emptyList()
        if (asleep.isNotEmpty()) Banner(t("sysdata.hint.ownSleeps", "functions" to asleep.joinToString(", ") { tr.t("sysdata.fn.${it.str("id")}") }), tone = Tone.Warn)
    }

    val configFn = functions.firstOrNull { it.str("id") == "config" }
    if (configFn != null) {
        SettingGroup(t("sysdata.group.config")) {
            val backup = config.o("backup")
            val settings = backup.o("settings")
            val pace = if (settings.str("pace") == "daily") "daily" else "change"
            val passwordOn = settings.bool("encrypt") && config.bool("passwordSet")
            SettingRow(
                t("sysdata.fn.config"),
                id = "config",
                icon = WebIcons[SYSDATA_ICONS.getValue("config")],
                desc = configSummary(tr, config),
                control = { Choice(configFn) },
            ) {
                SettingSub(t("sysdata.config.when"), desc = t(if (pace == "daily") "sysdata.config.when.daily" else "sysdata.config.when.change"), control = {
                    Select(pace, listOf("daily" to t("sysdata.config.pace.daily"), "change" to t("sysdata.config.pace.change")), { if (it != pace) setPace(it) }, label = t("sysdata.config.when"), enabled = !run.running && config != null)
                })
                SettingSub(t("sysdata.config.password"), desc = t(if (passwordOn) "sysdata.config.password.set" else "sysdata.config.password.none"), control = {
                    SmallButton(t(if (passwordOn) "sysdata.config.password.change" else "sysdata.config.password.setUp"), { a.openPage("backups/configuration") })
                })
                SettingSub(t("sysdata.config.copies"), desc = t("sysdata.config.copies.desc"), control = {
                    SmallButton(t("sysdata.config.copies.show"), { a.openPage("backups/configuration") })
                })
                SettingSub(t("sysdata.config.more"), desc = t("sysdata.config.more.desc"), control = {
                    SmallButton(t("sysdata.config.more.open"), { a.openPage("backups/configuration") })
                })
            }
        }
    }

    SettingGroup(t("sysdata.group.logs")) {
        for (id in SYSDATA_LOGS) {
            val f = functions.firstOrNull { it.str("id") == id } ?: continue
            val parts = buildList {
                add(t("sysdata.fn.$id.desc"))
                when {
                    !f.bool("enabled") -> add(t("sysdata.off"))
                    f.bool("moving") -> add(t("sysdata.moving"))
                    f.longOrNull("sizeBytes") != null -> add(Fmt.bytes(tr, f.long("sizeBytes")))
                }
            }
            SettingRow(t("sysdata.fn.$id"), id = "sysdata-$id", icon = WebIcons[SYSDATA_ICONS.getValue(id)], desc = parts.joinToString(" · "), control = { Choice(f) })
        }
        RunnerState(run)
    }

    // Was ein Umzug neben die Daten gelegt hat: jemand sollte es ansehen.
    for (f in functions.filter { it.str("volume").isNotEmpty() && it.strings("aside").isNotEmpty() }) {
        val aside = f.strings("aside")
        val fn = t("sysdata.fn.${f.str("id")}")
        val folder = if (f.str("id") == "config") "backups" else f.str("id")
        Banner(
            t("sysdata.aside.text") + "\n" + aside.joinToString("\n") { io.github.ra5on.titanos.settings.pages.backups.breakable("/mnt/${f.str("volume")}/.titanos/$folder/$it") },
            tone = Tone.Warn,
            title = if (aside.size == 1) t("sysdata.aside.one", "function" to fn) else t("sysdata.aside.many", "function" to fn, "count" to aside.size),
        )
    }
}

private fun configSummary(tr: Tr, config: JsonObject?): String {
    if (config == null) return tr.t("sysdata.fn.config.desc")
    val backup = config.o("backup")
    val backups = backup.a("backups").objects()
    return when {
        !backup.o("settings").bool("enabled") -> tr.t("sysdata.off")
        backup.str("lastError").isNotEmpty() -> tr.t("sysdata.config.failed")
        backups.isEmpty() -> tr.t("sysdata.config.none")
        else -> listOf(
            tr.t("sysdata.config.last", "time" to Fmt.dateTime(tr, backups[0].str("time"))),
            tr.t("sysdata.config.count", "count" to backups.size),
            Fmt.bytes(tr, backups.sumOf { it.long("sizeBytes") }),
        ).joinToString(" · ")
    }
}
