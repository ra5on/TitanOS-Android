package io.github.ra5on.titanos.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.R
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/*
 * Haftungsausschluss (Inhaber 23.11.2026, Text wie DISCLAIMER.md im Repo).
 * Beim ersten Koppeln einmal zu bestätigen, bestehende Installationen fragen
 * einmal beim nächsten Start. Gemerkt wird je Installation mit der Fassung des
 * Textes: ein neuer Text (DISCLAIMER_VERSION + 1) fragt noch einmal. Entkoppeln
 * vergisst die Bestätigung nicht. Hintergrundarbeit (Mitteilungen,
 * Foto-Sicherung) hängt nie daran.
 */

/** Fassung des Textes; erhöhen, wenn sich der Text so ändert, dass er neu bestätigt werden soll. */
const val DISCLAIMER_VERSION = 1

object DisclaimerStore {
    private const val PREFS = "titan-disclaimer"

    fun accepted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("accepted", 0) >= DISCLAIMER_VERSION

    fun accept(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("accepted", DISCLAIMER_VERSION).putLong("at", System.currentTimeMillis()).apply()
    }
}

/** Der Text des Haftungsausschlusses (Absätze und Punkte, nichts abgeschnitten). */
@Composable
fun DisclaimerText(modifier: Modifier = Modifier) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_disclaimer_intro), style = TitanType.body, color = c.text)
        for ((label, text) in listOf(
            R.string.ui_disclaimer_data_label to R.string.ui_disclaimer_data,
            R.string.ui_disclaimer_plugins_label to R.string.ui_disclaimer_plugins,
            R.string.ui_disclaimer_remote_label to R.string.ui_disclaimer_remote,
            R.string.ui_disclaimer_promise_label to R.string.ui_disclaimer_promise,
        )) {
            val bold = stringResource(label)
            val rest = stringResource(text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = TitanType.body, color = c.text)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
                        append(" ")
                        append(rest)
                    },
                    style = TitanType.body,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(stringResource(R.string.ui_disclaimer_liability), style = TitanType.body, color = c.text)
        Text(stringResource(R.string.ui_disclaimer_license), style = TitanType.body, color = c.muted)
    }
}

/** Seite „Haftungsausschluss“ (Über die App). */
@Composable
fun DisclaimerPage(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.ui_disclaimer_title), onBack = onBack, backLabel = stringResource(R.string.ui_back)) } }) {
        DisclaimerText(Modifier.padding(top = 16.dp, bottom = 8.dp))
    }
}

/** Ankreuzfeld „gelesen und verstanden“ mit dem Weg zum ganzen Text (Koppeln). */
@Composable
fun DisclaimerCheck(checked: Boolean, onChange: (Boolean) -> Unit, onRead: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        CheckRow(checked, onChange, stringResource(R.string.ui_disclaimer_check))
        TitanButton(stringResource(R.string.ui_disclaimer_read), onRead, Modifier.padding(top = 4.dp))
    }
}

/**
 * Für bestehende Installationen: einmal lesen und bestätigen (beim nächsten
 * Start nach dem Update). Nur die Oberfläche wartet darauf.
 */
@Composable
fun DisclaimerGate(onAccept: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    val c = Titan.colors
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.ui_disclaimer_title)) } },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                CheckRow(checked, { checked = it }, stringResource(R.string.ui_disclaimer_check))
                TitanButton(stringResource(R.string.ui_disclaimer_continue), onAccept, Modifier.fillMaxWidth().padding(top = 8.dp), ButtonKind.Primary, enabled = checked)
            }
        },
    ) {
        Text(stringResource(R.string.ui_disclaimer_ask), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).semantics { heading() })
        DisclaimerText()
    }
}
