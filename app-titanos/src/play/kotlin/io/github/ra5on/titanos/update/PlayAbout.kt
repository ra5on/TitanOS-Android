package io.github.ra5on.titanos.update

import android.content.Context
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.ra5on.titanos.R
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/*
 * Bauvariante „play“: kein Selbst-Update (Google Play verbietet es,
 * REQUEST_INSTALL_PACKAGES „may not be used to perform self updates“,
 * Play-Hilfe, gelesen 22.11.2026). Dieselben Namen wie feature-update, damit
 * AppRoot, MainActivity, TitanApp und der Arbeitsplatz für beide Varianten
 * gleich bleiben: Updates kommen hier über Google Play.
 */

/** Keine eigene Suche nach Updates. */
object UpdateCheck {
    @Suppress("UNUSED_PARAMETER")
    fun schedule(context: Context) = Unit
}

/** Nur der Name des Extras (MainActivity); eine Update-Mitteilung gibt es hier nicht. */
object UpdateWorker {
    const val EXTRA_OPEN_ABOUT = "io.github.ra5on.titanos.OPEN_ABOUT"
}

private const val AGENT_PACKAGE = "io.github.ra5on.titanos.agent"

@Composable
private fun InstalledIcon(packageName: String, size: Dp, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) fallback() else Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}

private fun versionOf(context: Context, packageName: String): String? =
    runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

/** Die Seite einer App bei Google Play (App „Play Store“, sonst im Browser). */
private fun openStore(context: Context, packageName: String) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** „Über die App“ der Play-Variante: Version, NAS, Agent-App; Updates über Google Play. */
@Composable
fun AboutScreen(nasVersion: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = Titan.colors
    val mine = versionOf(context, context.packageName).orEmpty()
    val agent = versionOf(context, AGENT_PACKAGE)
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.play_about_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InstalledIcon(context.packageName, 72.dp) { AppIcon("storage", "dark", 72.dp) }
            Text(stringResource(R.string.play_about_name), style = TitanType.title, color = c.text)
            Text(
                listOfNotNull(stringResource(R.string.play_about_version, mine), nasVersion?.let { stringResource(R.string.play_about_nas, it) }).joinToString(" · "),
                style = TitanType.body,
                color = c.muted,
            )
        }
        Card {
            ListRow(
                stringResource(R.string.play_about_updates),
                sub = stringResource(R.string.play_about_updates_text),
                right = { TitanButton(stringResource(R.string.play_about_store), { openStore(context, context.packageName) }) },
            )
        }
        SectionHeader(stringResource(R.string.play_about_agent))
        Card {
            ListRow(
                stringResource(R.string.play_about_agent),
                sub = if (agent == null) stringResource(R.string.play_about_agent_missing) else stringResource(R.string.play_about_version, agent),
                icon = { InstalledIcon(AGENT_PACKAGE, 30.dp) { AppIcon("agent", "ember", 30.dp) } },
                right = if (agent == null) ({ TitanButton(stringResource(R.string.play_about_store), { openStore(context, AGENT_PACKAGE) }) }) else null,
            )
        }
        Note(stringResource(R.string.play_about_note), Modifier.padding(top = 12.dp))
        Box(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}
