package io.github.ra5on.titanos.watch.pair

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.MaterialTheme
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.net.Spki
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.WatchGraph
import io.github.ra5on.titanos.watch.graph
import io.github.ra5on.titanos.watch.ui.Busy
import io.github.ra5on.titanos.watch.ui.Header
import io.github.ra5on.titanos.watch.ui.Page
import io.github.ra5on.titanos.watch.ui.Problem
import io.github.ra5on.titanos.watch.ui.RowButton
import io.github.ra5on.titanos.watch.ui.WText
import io.github.ra5on.titanos.watch.ui.WatchColors
import io.github.ra5on.titanos.watch.ui.TextEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** Was der Kopplungsbildschirm gerade zeigt. */
sealed interface PairView {
    data object Intro : PairView
    data class Entry(val initial: String) : PairView
    data class Connecting(val address: String) : PairView
    data class Code(val started: WatchStarted) : PairView
    data class Failed(val message: Int, val address: String?) : PairView
}

fun pairError(e: Throwable): Int = when {
    e is ApiException && e.code == "watch_certificate" -> R.string.w_pair_err_cert
    e is ApiException && e.status == 429 -> R.string.w_pair_err_limit
    e is ApiException && e.code == "pairing_code" -> R.string.w_pair_err_expired
    e is ApiException && e.status == 404 -> R.string.w_pair_err_old_nas
    e is IOException -> R.string.w_pair_err_reach
    else -> R.string.w_pair_err_other
}

/**
 * Koppeln ohne Google: Adresse vom Handy eintippen, sechs Ziffern auf der
 * Uhr, am Handy unter „Uhr koppeln“ eingeben (docs/ANDROID-APP.md „Die Uhr“).
 */
@Composable
fun PairScreen(initial: PairView = PairView.Intro, unpairedByNas: Boolean = false) {
    val context = LocalContext.current
    var view by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    fun connect(raw: String) {
        val address = ServerAddress.parse(raw.removePrefix("https://").trimEnd('/'))
        if (address == null) {
            view = PairView.Failed(R.string.w_pair_err_address, raw)
            return
        }
        view = PairView.Connecting(address.server)
        scope.launch {
            view = try {
                val g = context.graph
                PairView.Code(WatchPairClient(g.key, g.shared).start(address, WatchGraph.deviceName(context)))
            } catch (e: Exception) {
                PairView.Failed(pairError(e), address.server)
            }
        }
    }
    val label = stringResource(R.string.w_pair_address_label)
    when (val v = view) {
        PairView.Intro -> Page {
            item { Header(stringResource(R.string.w_pair_title)) }
            if (unpairedByNas) item { Problem(stringResource(R.string.w_pair_ended)) }
            item { WText(stringResource(R.string.w_pair_intro), center = true, modifier = Modifier.padding(horizontal = 6.dp)) }
            item { RowButton(stringResource(R.string.w_pair_address), onClick = { view = PairView.Entry("") }, icon = TitanIcons.edit, filled = true) }
        }
        is PairView.Entry -> TextEntry(label, v.initial, uri = true, onDone = { connect(it) }, onCancel = { view = PairView.Intro })
        is PairView.Connecting -> Page {
            item { Header(stringResource(R.string.w_pair_title)) }
            item { Busy(stringResource(R.string.w_pair_connecting, v.address)) }
        }
        is PairView.Code -> CodePage(v.started, onExpired = { view = PairView.Failed(R.string.w_pair_err_expired, v.started.address.server) })
        is PairView.Failed -> Page {
            item { Header(stringResource(R.string.w_pair_title)) }
            item { Problem(stringResource(v.message)) }
            if (v.address != null) item { RowButton(stringResource(R.string.w_pair_again), onClick = { connect(v.address) }, icon = TitanIcons.restart, filled = true) }
            item { RowButton(stringResource(R.string.w_pair_other_address), onClick = { view = PairView.Entry(v.address.orEmpty()) }, icon = TitanIcons.edit) }
        }
    }
}

@Composable
private fun CodePage(s: WatchStarted, onExpired: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Nachfragen nur, solange die Seite sichtbar ist (Akku), alle 2,5 Sekunden.
    LaunchedEffect(s.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            poll(context, s, onExpired)
        }
    }
    CodeContent(
        title = s.hostname.ifBlank { s.address.server },
        code = s.code,
        fingerprint = if (s.probed.publiclyValid) null else Spki.displayLines(s.probed.hash),
    )
}

/** Die sechs Ziffern groß, darunter was zu tun ist und der Fingerabdruck des gesehenen Zertifikats. */
@Composable
fun CodeContent(title: String, code: String, fingerprint: List<String>?) {
    val spoken = code.toList().joinToString(" ")
    Page {
        item { Header(title) }
        item {
            WText(
                WatchCode.display(code),
                center = true,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 2.sp),
                color = WatchColors.accent,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
            )
        }
        item { WText(stringResource(R.string.w_pair_code_hint), center = true, modifier = Modifier.padding(horizontal = 6.dp)) }
        if (fingerprint != null) {
            item {
                WText(
                    stringResource(R.string.w_pair_fingerprint, fingerprint.joinToString("\n")),
                    center = true,
                    color = WatchColors.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private suspend fun poll(context: Context, s: WatchStarted, onExpired: () -> Unit) {
    val g = context.graph
    val client = WatchPairClient(g.key, g.shared)
    while (true) {
        val r = try {
            client.waitOnce(s)
        } catch (e: ApiException) {
            if (e.status == 404) {
                onExpired()
                return
            }
            null
        } catch (_: IOException) {
            null
        }
        if (r != null && r.state == "confirmed") {
            WatchPairClient.pairing(s, r)?.let { g.paired(it) } ?: onExpired()
            return
        }
        delay(2500)
    }
}
