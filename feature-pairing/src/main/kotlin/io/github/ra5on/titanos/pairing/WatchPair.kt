package io.github.ra5on.titanos.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.IOException

@Serializable
internal data class WatchConfirmRequest(val code: String)

@Serializable
data class WatchConfirmed(val state: String = "", val id: String = "", val name: String = "", val app: String = "")

/**
 * „Uhr koppeln“ (PLAN 116 Paket G, docs/ANDROID-APP.md „Die Uhr“): Die Uhr
 * hat keine Kamera und kein Google, sie meldet sich selbst unter einer der
 * Adressen und zeigt sechs Ziffern; die gekoppelte App bestätigt sie aus
 * ihrer App-Sitzung. Die Uhr wird ein eigenes Gerät, einzeln entziehbar.
 */
object WatchPair {
    suspend fun confirm(nas: NasConnection, code: String): WatchConfirmed = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(it.newBuilder().encodedPath("/api/v1/app/pair/watch/confirm").build())
                .post(jsonBody(WatchConfirmRequest(code.filter(Char::isDigit)))).build()
        }.decode<WatchConfirmed>()
    }

    /** Fehler in Worten (Ressource). */
    fun errorText(e: Throwable): Int = when {
        e is ApiException && e.code == "pairing_code" -> R.string.pair_watch_err_code
        e is ApiException && e.status == 429 -> R.string.pair_watch_err_limit
        e is ApiException && e.status == 404 -> R.string.pair_watch_err_old_nas
        e is ApiException && e.code == "app_limit" -> R.string.pair_watch_err_app_limit
        e is IOException -> R.string.pair_watch_err_reach
        else -> R.string.pair_watch_err_other
    }
}

@Composable
fun WatchPairScreen(addresses: List<String>, nas: NasConnection?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var done by remember { mutableStateOf<WatchConfirmed?>(null) }
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.pair_watch_title), onBack = onBack, backLabel = stringResource(R.string.pair_watch_back)) } },
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(12.dp))
        val ok = done
        if (ok != null) {
            Note(stringResource(R.string.pair_watch_done, ok.name), icon = TitanIcons.check)
            Spacer(Modifier.height(16.dp))
            TitanButton(stringResource(R.string.pair_watch_close), onClick = onBack, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
            return@Page
        }
        Text(stringResource(R.string.pair_watch_intro), style = TitanType.body, color = Titan.colors.text)
        SectionHeader(stringResource(R.string.pair_watch_step1))
        Card {
            for ((i, a) in addresses.withIndex()) {
                ListRow(a, icon = { io.github.ra5on.titanos.ui.components.RowIcon(TitanIcons.globe) }, divider = i > 0)
            }
        }
        SectionHeader(stringResource(R.string.pair_watch_step2))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                code,
                { v -> code = v.filter { it.isDigit() || it == ' ' }.take(7); error = null },
                label = stringResource(R.string.pair_watch_code_label),
                placeholder = "482 913",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            error?.let { Problem(stringResource(it)) }
            TitanButton(
                stringResource(if (busy) R.string.pair_watch_busy else R.string.pair_watch_confirm),
                enabled = !busy && nas != null && code.count(Char::isDigit) == 6,
                kind = ButtonKind.Primary,
                icon = TitanIcons.check,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val conn = nas ?: return@TitanButton
                    busy = true
                    scope.launch {
                        try {
                            done = WatchPair.confirm(conn, code)
                        } catch (e: Exception) {
                            error = WatchPair.errorText(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            Text(stringResource(R.string.pair_watch_hint), style = TitanType.small, color = Titan.colors.muted)
        }
    }
}
