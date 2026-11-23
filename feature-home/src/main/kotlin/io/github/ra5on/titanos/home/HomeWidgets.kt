package io.github.ra5on.titanos.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk

/**
 * Inhalt eines Widgets der Startseite (Entwurf 04): Zustand wie bisher die
 * Karte oben, Speicher je Volume, Foto-Sicherung, Container und VMs. Alles mit
 * Worten, nicht nur mit Farbe; Tippen auf das Widget öffnet das Programm.
 */
@Composable
internal fun WidgetBody(kind: String, state: HomeState, photos: String?, onPhotos: (() -> Unit)?) {
    val c = Titan.colors
    when (kind) {
        "status" -> StatusCard(state, photos, onPhotos)
        "volumes" -> WidgetCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.home_widget_volumes), style = TitanType.rowTitle, color = c.text)
                val vols = state.volumes?.filter { it.sizeBytes > 0 }
                when {
                    vols == null -> Text(stringResource(R.string.home_loading), style = TitanType.small, color = c.muted)
                    vols.isEmpty() -> Text(stringResource(R.string.home_no_volumes), style = TitanType.small, color = c.muted)
                    else -> for (v in vols) {
                        Column(Modifier.fillMaxWidth()) {
                            val (used, total) = Format.bytesOf(v.usedBytes, v.sizeBytes)
                            Text(stringResource(R.string.home_storage_sub, v.name, v.level.uppercase().replace("RAID", "RAID ")), style = TitanType.body, color = c.text)
                            Text(stringResource(R.string.home_storage_of, used, total), style = TitanType.small, color = c.muted)
                            Bar(v.usedBytes.toFloat() / v.sizeBytes, Modifier.padding(top = 6.dp))
                            if (v.state != "ok") Text(stringResource(R.string.home_widget_volume_state, v.state), style = TitanType.small, color = c.warnInk, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        "photos" -> WidgetCard {
            Column {
                Text(stringResource(R.string.home_photos_title), style = TitanType.rowTitle, color = c.text)
                Text(photos ?: stringResource(R.string.home_photos_later), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        "docker", "vms" -> WidgetCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(if (kind == "docker") R.string.home_widget_docker else R.string.home_widget_vms), style = TitanType.rowTitle, color = c.text)
                val list = if (kind == "docker") state.docker else state.vms
                when {
                    list == null -> Text(stringResource(R.string.home_loading), style = TitanType.small, color = c.muted)
                    list.isEmpty() -> Text(stringResource(if (kind == "docker") R.string.home_widget_none_docker else R.string.home_widget_none_vms), style = TitanType.small, color = c.muted)
                    else -> {
                        // Laufende zuerst; höchstens sechs Zeilen, der Rest als Zahl.
                        val sorted = list.sortedByDescending { it.running }
                        for (item in sorted.take(6)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (item.running) c.okInk else c.muted))
                                Text(item.label, style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
                                Text(stringResource(if (item.running) R.string.home_widget_running else R.string.home_widget_stopped), style = TitanType.small, color = if (item.running) c.okInk else c.muted)
                            }
                        }
                        if (sorted.size > 6) Text(stringResource(R.string.home_widget_more, sorted.size - 6), style = TitanType.small, color = c.muted)
                    }
                }
            }
        }
        else -> Unit
    }
}

/** Glyphe und Verlauf des Widgets unter „Hinzufügen“ (wie die Programmsymbole). */
internal fun widgetIcon(kind: String): Pair<String, String> = when (kind) {
    "status" -> "overview" to "green"
    "volumes" -> "storage" to "blue"
    "photos" -> "photos" to "green"
    "docker" -> "docker" to "blue"
    else -> "vm" to "violet"
}
