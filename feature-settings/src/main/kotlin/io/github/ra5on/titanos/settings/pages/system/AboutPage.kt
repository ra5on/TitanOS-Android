package io.github.ra5on.titanos.settings.pages.system

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons

/**
 * Über TitanOS (routes/settings/about): was läuft und unter welchen Lizenzen,
 * aus `GET /about`; der Steckplatz aus `GET /system` (image.slot).
 */
@Composable
fun AboutPage(a: PageArgs) {
    val env = LocalEnv.current
    val context = LocalContext.current
    val state = rememberLoad { env.api.obj("about") }
    val slot = rememberLoad { runCatching { env.api.obj("system").o("image").str("slot") }.getOrDefault("") }
    Loading(state) { about ->
        val license = about.str("license")
        val third = about.str("thirdParty")
        SettingGroup {
            Wordmark()
            SettingRow(t("about.version"), icon = WebIcons["info"], control = { Mono(Fmt.osVersion(about.str("version"))) })
            val s = slot.value.orEmpty().uppercase()
            if (s.isNotEmpty()) SettingRow(t("about.slot"), icon = WebIcons["disc"], control = { Mono(s) })
            SettingRow(t("about.kernel"), icon = WebIcons["chip"], control = { Mono(about.str("kernel").ifEmpty { "–" }) })
            SettingRow(t("about.base"), icon = WebIcons["linux"], control = { Value(t("about.baseText")) })
            SettingRow(t("about.source"), icon = WebIcons["external"], control = {
                Text(
                    buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("github.com/ra5on/TitanOS") } },
                    style = TitanType.rowSub,
                    color = Titan.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .then(Modifier.semantics { contentDescription = "github.com/ra5on/TitanOS" })
                        .clickable(role = Role.Button) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ra5on/TitanOS")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            })
            if (env.admin) SettingRow(t("about.setupList"), icon = WebIcons["list"], onClick = { env.open("/welcome?step=4") })
        }
        SettingGroup(t("about.licenses")) {
            val licenseDesc = t("about.licenseText") + "\n" + t("about.licenseLater")
            if (license.isNotEmpty()) {
                SettingRow(t("about.license"), desc = licenseDesc, icon = WebIcons["fileText"], summary = "FSL-1.1-ALv2", id = "about-license") {
                    Block { Pre(license, scroll = false) }
                }
            } else {
                SettingRow(t("about.license"), desc = licenseDesc, icon = WebIcons["fileText"], summary = "FSL-1.1-ALv2")
            }
            if (third.isNotEmpty()) {
                SettingRow(t("about.thirdParty"), desc = t("about.thirdPartyText"), icon = WebIcons["box"], id = "about-third") {
                    Block { Pre(third, scroll = true) }
                }
            } else {
                SettingRow(t("about.thirdParty"), desc = t("about.thirdPartyText"), icon = WebIcons["box"])
            }
        }
    }
}

/** Das Logo (Wortmarke) über den Zeilen. */
@Composable
private fun Wordmark() {
    val c = Titan.colors
    Text(
        buildAnnotatedString {
            append("TITAN")
            withStyle(SpanStyle(color = c.accent)) { append("OS") }
        },
        style = TitanType.big.copy(fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
        color = c.text,
        modifier = Modifier.padding(bottom = 8.dp, start = 2.dp).semantics { heading(); contentDescription = "TitanOS" },
    )
}

/** Ein Lizenztext in Mono; der lange Text der Fremdsoftware scrollt in seinem Kasten. */
@Composable
private fun Pre(text: String, scroll: Boolean) {
    val c = Titan.colors
    val style = TitanType.small.copy(fontFamily = TitanFonts.mono, lineHeight = 19.sp)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.track).padding(12.dp)) {
        if (scroll) {
            Box(Modifier.heightIn(max = 448.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(text, style = style, color = c.text, softWrap = false)
            }
        } else {
            Text(text, style = style, color = c.text)
        }
    }
}

