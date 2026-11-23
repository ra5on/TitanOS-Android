package io.github.ra5on.titanos.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import kotlinx.coroutines.launch
import java.time.Instant

/** Das echte Symbol einer installierten App (das „T“, das Agent-Zeichen); fehlt sie, das Programmsymbol. */
@Composable
private fun InstalledIcon(packageName: String, size: Dp, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) fallback() else Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}

/** „Über die App“ mit Selbst-Update (Entwurf 91). */
@Composable
fun AboutScreen(nasVersion: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { UpdateSettings(context) }
    var auto by remember { mutableStateOf(settings.autoCheck) }
    var test by remember { mutableStateOf(settings.includeTest) }
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    val mineName = Installer.versionName(context, AppKind.TitanOS).orEmpty()
    // Die alte Agent-App (bis 1.00), falls noch installiert: wird nicht mehr aktualisiert.
    val agentName = remember { runCatching { context.packageManager.getPackageInfo(AppKind.OLD_AGENT_PACKAGE, 0).versionName }.getOrNull() }
    LaunchedEffect(Unit) { UpdateCheck.run(context) }
    // Haftungsausschluss (Inhaber 23.11.2026) als eigene Seite.
    var disclaimer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (disclaimer) {
        io.github.ra5on.titanos.ui.components.DisclaimerPage(onBack = { disclaimer = false })
        return
    }

    fun install(kind: AppKind) {
        val release = UpdateState.available ?: return
        if (!Installer.canInstall(context)) {
            context.startActivity(Installer.allowInstallIntent(context))
            return
        }
        scope.launch {
            try {
                Installer(context, UpdateCheck.client).install(release, kind)
            } catch (e: Exception) {
                UpdateState.step = UpdateStep.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.upd_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InstalledIcon(context.packageName, 72.dp) { AppIcon("storage", "dark", 72.dp) }
            Text(stringResource(R.string.upd_app_name), style = TitanType.title, color = c.text)
            Text(
                listOfNotNull(stringResource(R.string.upd_version, mineName), nasVersion?.let { stringResource(R.string.upd_nas, it) }).joinToString(" · "),
                style = TitanType.body,
                color = c.muted,
            )
        }
        val step = UpdateState.step
        val found = UpdateState.available
        if (found != null) {
            Card {
                ListRow(
                    stringResource(R.string.upd_available, found.versionName),
                    sub = stringResource(R.string.upd_available_text, Format.bytes(found.sizes[AppKind.TitanOS] ?: 0)),
                    icon = { RowIcon(TitanIcons.update, tint = c.accent) },
                )
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    UpdateAction(step, AppKind.TitanOS, onInstall = { install(AppKind.TitanOS) })
                }
            }
            if (found.notes.isNotBlank()) {
                SectionHeader(stringResource(R.string.upd_new_in, found.versionName))
                Card { Text(found.notes, style = TitanType.body, color = c.text, modifier = Modifier.padding(14.dp)) }
            }
        } else {
            Card {
                ListRow(
                    if (step == UpdateStep.Checking) stringResource(R.string.upd_checking) else stringResource(R.string.upd_current),
                    sub = UpdateState.lastCheckMs.takeIf { it > 0 }?.let {
                        stringResource(R.string.upd_checked, Format.shortDate(Instant.ofEpochMilli(it), stringResource(R.string.upd_today), stringResource(R.string.upd_yesterday)))
                    },
                    icon = { RowIcon(TitanIcons.check, tint = c.okInk) },
                    right = { TitanButton(stringResource(R.string.upd_check_now), { scope.launch { UpdateCheck.run(context) } }) },
                )
            }
            UpdateState.lastError?.let { Problem(stringResource(R.string.upd_check_failed), Modifier.padding(top = 10.dp)) }
        }
        if (step is UpdateStep.Failed) Problem(stringResource(R.string.upd_failed, step.reason), Modifier.padding(top = 10.dp))
        if (!Installer.canInstall(context)) {
            Note(stringResource(R.string.upd_allow_note), Modifier.padding(top = 12.dp))
        }

        SectionHeader(stringResource(R.string.upd_section))
        Card {
            ListRow(
                stringResource(R.string.upd_auto),
                sub = stringResource(R.string.upd_auto_text),
                right = { Toggle(auto, { auto = it; settings.autoCheck = it; UpdateCheck.schedule(context) }, on, off) },
            )
            ListRow(
                stringResource(R.string.upd_test),
                sub = stringResource(R.string.upd_test_text),
                right = { Toggle(test, { test = it; settings.includeTest = it; scope.launch { UpdateCheck.run(context) } }, on, off) },
                divider = true,
            )
        }
        // Ab 1.01 ist der KI-Agent ganz in dieser App; die Agent-App bekommt keine Updates mehr.
        if (agentName != null) {
            SectionHeader(stringResource(R.string.upd_agent))
            Card {
                ListRow(
                    stringResource(R.string.upd_agent_old, agentName),
                    sub = stringResource(R.string.upd_agent_old_text),
                    icon = { InstalledIcon(AppKind.OLD_AGENT_PACKAGE, 30.dp) { AppIcon("agent", "ember", 30.dp) } },
                )
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    TitanButton(stringResource(R.string.upd_agent_info), {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${AppKind.OLD_AGENT_PACKAGE}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }, Modifier.fillMaxWidth())
                }
            }
        }
        Card(Modifier.padding(top = 18.dp)) {
            ListRow(
                stringResource(UiR.string.ui_disclaimer_title),
                icon = { RowIcon(TitanIcons.info) },
                chevron = true,
                onClick = { disclaimer = true },
            )
        }
        Text(stringResource(R.string.upd_signature), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        Box(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}

@Composable
private fun UpdateAction(step: UpdateStep, kind: AppKind, onInstall: () -> Unit) {
    val c = Titan.colors
    when {
        step is UpdateStep.Downloading && step.kind == kind -> Column {
            Text(stringResource(R.string.upd_downloading, Format.bytes(step.done), Format.bytes(step.total)), style = TitanType.small, color = c.muted)
            Bar(if (step.total > 0) step.done.toFloat() / step.total else 0f, Modifier.padding(top = 8.dp))
        }
        step is UpdateStep.Installing && step.kind == kind -> Text(stringResource(R.string.upd_installing), style = TitanType.body, color = c.muted)
        step is UpdateStep.NeedsConfirm && step.kind == kind -> Text(stringResource(R.string.upd_confirm_text), style = TitanType.body, color = c.muted)
        else -> TitanButton(stringResource(R.string.upd_install), onInstall, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}

const val RELEASES_PAGE = "https://github.com/ra5on/TitanOS-Android/releases"
