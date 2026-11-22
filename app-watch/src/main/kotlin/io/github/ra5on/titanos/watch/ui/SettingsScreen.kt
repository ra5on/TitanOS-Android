package io.github.ra5on.titanos.watch.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.update.AppKind
import io.github.ra5on.titanos.update.Installer
import io.github.ra5on.titanos.update.UpdateCheck
import io.github.ra5on.titanos.update.UpdateState
import io.github.ra5on.titanos.update.UpdateStep
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.graph
import kotlinx.coroutines.launch

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

@Composable
fun SettingsRoute() {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    var readAloud by remember { mutableStateOf(g.settings.readAloud) }
    var nasVoice by remember { mutableStateOf(g.settings.nasVoice) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    val step = UpdateState.step
    val found = UpdateState.available
    Page {
        item { Header(stringResource(R.string.w_settings)) }
        item {
            SwitchButton(
                checked = readAloud,
                onCheckedChange = { readAloud = it; g.settings.readAloud = it },
                modifier = Modifier.fillMaxWidth(),
                label = { WText(stringResource(R.string.w_set_read_aloud), style = MaterialTheme.typography.labelMedium) },
            )
        }
        item {
            SwitchButton(
                checked = nasVoice,
                onCheckedChange = { nasVoice = it; g.settings.nasVoice = it },
                modifier = Modifier.fillMaxWidth(),
                label = { WText(stringResource(R.string.w_set_nas_voice), style = MaterialTheme.typography.labelMedium) },
                secondaryLabel = { WText(stringResource(R.string.w_set_nas_voice_sub), style = MaterialTheme.typography.labelSmall) },
            )
        }
        item { Header(stringResource(R.string.w_update)) }
        item { WText(stringResource(R.string.w_version, versionName(context)), center = true) }
        when {
            step is UpdateStep.Checking -> item { Busy(stringResource(R.string.w_update_checking)) }
            step is UpdateStep.Downloading -> item {
                Busy(if (step.total > 0) stringResource(R.string.w_update_loading_of, Format.bytes(step.done), Format.bytes(step.total)) else stringResource(R.string.w_update_loading))
            }
            step is UpdateStep.Installing || step is UpdateStep.NeedsConfirm -> item { Busy(stringResource(R.string.w_update_installing)) }
            found != null -> {
                item { WText(stringResource(R.string.w_update_found, found.versionName), center = true) }
                if (!Installer.canInstall(context)) {
                    item { WText(stringResource(R.string.w_update_allow_text), center = true, color = WatchColors.muted) }
                    item { RowButton(stringResource(R.string.w_update_allow), onClick = { context.startActivity(Installer.allowInstallIntent(context)) }, icon = TitanIcons.lock) }
                }
                item {
                    RowButton(stringResource(R.string.w_update_install), filled = true, icon = TitanIcons.download, onClick = {
                        scope.launch {
                            runCatching { Installer(context, UpdateCheck.client).install(found, AppKind.Watch) }
                                .onFailure { UpdateState.step = UpdateStep.Failed(it.message ?: "update") }
                        }
                    })
                }
            }
            else -> {
                if (checked && UpdateState.lastError == null) item { WText(stringResource(R.string.w_update_none), center = true, color = WatchColors.muted) }
                UpdateState.lastError?.let { item { Problem(stringResource(R.string.w_update_err)) } }
                item {
                    RowButton(stringResource(R.string.w_update_check), icon = TitanIcons.update, onClick = {
                        scope.launch { UpdateCheck.run(context); checked = true }
                    })
                }
            }
        }
        if (step is UpdateStep.Failed) item { Problem(stringResource(R.string.w_update_failed)) }
        item { Header(stringResource(R.string.w_pairing)) }
        g.pairing?.let { p -> item { WText(stringResource(R.string.w_paired_with, p.user, p.hostname.ifBlank { p.addresses.firstOrNull()?.server.orEmpty() }), center = true) } }
        item { RowButton(stringResource(R.string.w_unpair), onClick = { confirmUnpair = true }, icon = TitanIcons.x, danger = true) }
    }
    ConfirmDanger(
        visible = confirmUnpair,
        title = stringResource(R.string.w_unpair_title),
        text = stringResource(R.string.w_unpair_text),
        action = stringResource(R.string.w_unpair),
        onConfirm = { confirmUnpair = false; g.forget() },
        onDismiss = { confirmUnpair = false },
    )
}
