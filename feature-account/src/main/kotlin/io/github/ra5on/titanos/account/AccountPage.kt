package io.github.ra5on.titanos.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons

/**
 * Der Inhalt von „Mein Konto“ (routes/account/+page.svelte): Konto (Rolle,
 * Bild, App), Anmeldung (Passwort, 2FA, Passkeys, SSH, WebDAV,
 * Gerätepasswörter), angemeldete Geräte mit den Android-Apps, Glocke und
 * Töne bzw. Mitteilungen, Meine Benachrichtigungen, Notfallzugang. Am Handy
 * eine Spalte wie das Web am Handy; ab 1280 dp zwei Spalten.
 */
@Composable
fun AccountPage() {
    val env = LocalEnv.current
    val calls = remember(env) { AccountCalls(env.api) }
    val session = rememberLoad { calls.session() }
    val sessions = rememberLoad { calls.sessions() }
    Loading(session) { s ->
        val user = s.o("user")
        val ctx = AccountCtx(
            calls = calls,
            name = user.str("name", env.user()),
            role = user.str("role", env.role()),
            twoFactor = user.bool("twoFactor"),
            refreshSession = { session.refresh() },
            sessions = sessions,
        )
        if (currentWidth() == Width.Desktop) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { LeftSide(ctx) }
                Column(Modifier.weight(1f)) { RightSide(ctx) }
            }
        } else {
            LeftSide(ctx)
            RightSide(ctx)
        }
    }
}

@Composable
private fun LeftSide(ctx: AccountCtx) {
    SettingGroup(t("account.group.account")) {
        SettingRow(ctx.name, desc = "${t("account.role")}: ${t("role.${ctx.role}")}", icon = WebIcons["users"])
        PictureRow(ctx)
        // „TitanOS als App“: hier läuft sie schon als App (im Web: installieren).
        SettingRow(t("share.appTitle"), desc = t("share.appText"), icon = WebIcons["download"], control = { StatusChip(t("share.installed"), Tone.Ok) })
    }
    SettingGroup(t("account.group.login")) {
        PasswordRow(ctx)
        TwoFactorRow(ctx)
        PasskeysRow(ctx)
        SshKeysRow(ctx)
        WebDavRow(ctx)
        DavDevicesRow(ctx)
    }
    DevicesGroup(ctx)
}

@Composable
private fun RightSide(ctx: AccountCtx) {
    val prefs = rememberLoad { ctx.calls.preferences() }
    SettingGroup(t(if (ctx.admin) "account.group.bell" else "account.group.notify")) {
        if (!ctx.admin) {
            PushRow(ctx)
            CameraMailRow(ctx)
        }
        Loading(prefs) { _ ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ctx.admin) BellRow(ctx, prefs)
                SoundRow(ctx, prefs)
            }
        }
    }
    if (ctx.admin) PersonalNotifications(ctx)
    EmergencyGroup(ctx)
}
