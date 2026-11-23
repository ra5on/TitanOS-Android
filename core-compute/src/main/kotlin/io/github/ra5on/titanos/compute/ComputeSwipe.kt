package io.github.ra5on.titanos.compute

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.ui.components.SwipeAction
import io.github.ra5on.titanos.ui.components.SwipeRow
import io.github.ra5on.titanos.ui.generated.IconSquareColors
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons

/**
 * Zeile einer App, eines Containers, einer VM oder eines Systemcontainers
 * (Entwurf 12): nach rechts starten, nach links stoppen bzw. herunterfahren,
 * beides mit kurzer Rückfrage und ohne „Rückgängig“. [start] bzw. [stop] null:
 * diese Richtung passt gerade nicht (läuft schon, steht schon, belegt).
 * Neu starten bleibt im Menü „⋯“.
 */
@Composable
fun StartStopRow(
    name: String,
    start: (() -> Unit)?,
    stop: (() -> Unit)?,
    modifier: Modifier = Modifier,
    startLabel: String = t("docker.start"),
    stopLabel: String = t("docker.stop"),
    shutdown: Boolean = false,
    background: Color = Titan.colors.surface,
    content: @Composable () -> Unit,
) {
    val ui = LocalComputeUi.current
    val askStart = stringResource(R.string.compute_ask_start, name)
    val askStop = stringResource(if (shutdown) R.string.compute_ask_shutdown else R.string.compute_ask_stop, name)
    SwipeRow(
        start = start?.let { f ->
            SwipeAction(startLabel, TitanIcons.play, IconSquareColors.green) { ui.confirm(Ask(askStart, null, startLabel, danger = false) { f() }) }
        },
        end = stop?.let { f ->
            SwipeAction(stopLabel, TitanIcons.stop, IconSquareColors.orange) { ui.confirm(Ask(askStop, null, stopLabel, danger = false) { f() }) }
        },
        modifier = modifier,
        background = background,
        content = content,
    )
}
