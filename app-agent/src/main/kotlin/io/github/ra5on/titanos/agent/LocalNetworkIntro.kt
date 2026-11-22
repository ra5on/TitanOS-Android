package io.github.ra5on.titanos.agent

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.pairing.LOCAL_NETWORK
import io.github.ra5on.titanos.pairing.localNetworkGranted
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.BigTitle
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.pairing.R as PairR

/**
 * „Geräte im lokalen Netzwerk“ (Android 17, ACCESS_LOCAL_NETWORK) beim ersten
 * Start der Agent-App: Die Kopplung über die TitanOS-App (Sibling.kt) läuft im
 * Hintergrund, schon in `Application.onCreate`. Ohne die Erlaubnis scheitert
 * sie bei Adressen im Heimnetz still (Fund von F2). Deshalb fragt die App
 * einmal mit Erklärung und stößt danach die Kopplung erneut an.
 */
object LocalNetworkIntro {
    private const val PREFS = "agent-start"
    private const val ASKED = "local-network-asked"

    /** Zeigen: ab Android 17, Erlaubnis fehlt und noch nie gefragt. */
    fun needed(context: Context): Boolean =
        !localNetworkGranted(context) && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun asked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }
}

@Composable
fun LocalNetworkIntroScreen(onDone: (granted: Boolean) -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocalNetworkIntro.asked(context)
        onDone(granted)
    }
    val c = Titan.colors
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) },
        bottomBar = {
            Column(
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TitanButton(stringResource(PairR.string.pair_local_network_allow), { ask.launch(LOCAL_NETWORK) }, Modifier.fillMaxWidth(), ButtonKind.Primary)
                TitanButton(stringResource(R.string.agent_local_later), {
                    LocalNetworkIntro.asked(context)
                    onDone(false)
                }, Modifier.fillMaxWidth())
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon("shares", "yellow", 72.dp)
            BigTitle(stringResource(PairR.string.perm_local))
        }
        Text(stringResource(R.string.agent_local_text), style = TitanType.body, color = c.text, modifier = Modifier.padding(top = 8.dp))
        Note(stringResource(R.string.agent_local_note), Modifier.padding(top = 14.dp))
    }
}
