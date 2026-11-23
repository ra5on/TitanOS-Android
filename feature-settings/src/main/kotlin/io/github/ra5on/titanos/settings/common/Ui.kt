package io.github.ra5on.titanos.settings.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t

/**
 * Zeigt „Wird geladen …“, einen Fehler mit „Erneut versuchen“ oder den Inhalt
 * mit dem geladenen Wert – wie `loading`/`loadError` der Web-Seiten.
 */
@Composable
fun <T> Loading(state: Loaded<T>, content: @Composable ColumnScope.(T) -> Unit) {
    val tr = LocalTr.current
    val v = state.value
    Column(Modifier.fillMaxWidth()) {
        if (v == null && state.loading) LoadingLine(t("common.loading"))
        state.error?.let { e ->
            Banner(tr.errorOf(e), tone = Tone.Error, action = t("ui.retry"), onAction = { state.reload() })
        }
        if (v != null) content(v)
    }
}

/** „Wird gespeichert …“, Fehler und „Gespeichert“ eines [Runner] unter einer Gruppe. */
@Composable
fun RunnerState(run: Runner, savingKey: String = "ui.saving") {
    run.error?.let { Banner(it, tone = Tone.Error) }
    if (run.running) LoadingLine(t(savingKey))
}
