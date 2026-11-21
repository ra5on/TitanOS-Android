package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.t

/**
 * Diagnosepaket (tools/bundle): das NAS packt die Zip-Datei beim Abruf, die
 * App schreibt sie gleich in die Datei, die man im Speicherdialog von Android
 * gewählt hat (Downloads oder ein anderer Ort). Sie geht nirgends sonst hin.
 */
@Composable
internal fun BundlePage(ctx: ToolsCtx) {
    val saver = rememberSaver()
    fun pack() {
        saver.save("titanos-diagnose.zip", "application/zip") { out, progress -> ctx.api.download("diagnostics/bundle", out, progress) }
    }
    PageKeys(ctx) { e -> if (e.ctrlKey(Key.S)) { if (!saver.state.busy) pack(); true } else false }
    PageBody {
        Muted(t("tools.bundleText"))
        SettingGroup(null) {
            SettingRow(t("tools.bundleIn"), icon = "archive", desc = t("tools.bundleInText"))
            SettingRow(t("tools.bundleOut"), icon = "shield", desc = t("tools.bundleOutText"), divider = true)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Notice(t("tools.bundleCheck"), tone = Tone.Warn)
                if (saver.state.busy) Notice(t("tools.bundleStarted"), tone = Tone.Ok)
                SaveStatus(saver.state)
                ActionButton(t("tools.bundleMake"), ::pack, primary = true, enabled = !saver.state.busy, icon = "download")
            }
        }
    }
}
