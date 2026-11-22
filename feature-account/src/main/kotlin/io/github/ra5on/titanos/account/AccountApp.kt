package io.github.ra5on.titanos.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.shell.rememberSettingsTr
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.FindTarget
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.LocalFindTarget
import io.github.ra5on.titanos.ui.components.OverlayHost
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType

/**
 * „Mein Konto“ (routes/account): Bild, Passwort, 2FA, Passkeys, SSH-Schlüssel,
 * WebDAV, Geräte und Android-Apps, Sitzungen. [start] wie `/account#find=…`.
 */
@Composable
fun AccountApp(env: SettingsEnv, onClose: () -> Unit, start: String? = null) {
    val tr = rememberSettingsTr()
    val find = remember(start) {
        start?.substringAfter("#find=", "")?.takeIf { it.isNotEmpty() }?.split('|')?.map { java.net.URLDecoder.decode(it, "UTF-8") }?.let { FindTarget(it) }
    }
    CompositionLocalProvider(LocalTr provides tr, LocalEnv provides env, LocalFindTarget provides find) {
        OverlayHost {
            BackHandler(onBack = onClose)
            val c = Titan.colors
            Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().background(c.header).heightIn(min = 56.dp).padding(start = 6.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(TitanIcons.back, stringResource(io.github.ra5on.titanos.ui.R.string.ui_back), onClose, size = 24.dp)
                    Text(stringResource(R.string.acc_title), style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f))
                }
                Divider()
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Am Handy wie das Web am Handy; ab 900 dp breiter wie das Web am PC, ab 1280 dp zwei Spalten.
                    val max = when (io.github.ra5on.titanos.ui.currentWidth()) {
                        io.github.ra5on.titanos.ui.Width.Desktop -> 1600.dp
                        io.github.ra5on.titanos.ui.Width.Wide -> 1200.dp
                        else -> 1000.dp
                    }
                    Column(Modifier.widthIn(max = max).fillMaxWidth().padding(bottom = 24.dp)) {
                        AccountPage()
                    }
                }
            }
        }
    }
}
