package io.github.ra5on.titanos

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.widgets.redraw
import androidx.lifecycle.lifecycleScope
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import io.github.ra5on.titanos.widgets.NasWidget
import io.github.ra5on.titanos.widgets.PhotosWidget
import io.github.ra5on.titanos.widgets.WidgetNas
import io.github.ra5on.titanos.widgets.WidgetRefresh
import io.github.ra5on.titanos.widgets.WidgetRuntime
import io.github.ra5on.titanos.widgets.WidgetStore
import kotlinx.coroutines.launch

/**
 * Welches NAS ein Widget zeigt (Widgets „NAS-Zustand“ und „Fotos“, mehrere
 * NAS). Android öffnet die Seite beim Einstellen des Widgets (lange drücken ›
 * Widget einstellen); ohne Wahl folgt das Widget dem Umschalter der App.
 */
class WidgetNasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Zurück ohne Wahl: das Widget bleibt, wie es ist (configuration_optional).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            TitanTheme {
                WidgetNasPage(WidgetRuntime.nasList(), WidgetStore.chosen(this, id), onBack = { finish() }) { nas ->
                    WidgetStore.choose(this, id, nas)
                    lifecycleScope.launch {
                        runCatching {
                            NasWidget().redraw(this@WidgetNasActivity)
                            PhotosWidget().redraw(this@WidgetNasActivity)
                        }
                        WidgetRefresh.now(this@WidgetNasActivity)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
internal fun WidgetNasPage(list: List<WidgetNas>, chosen: String?, onBack: () -> Unit, onPick: (String?) -> Unit) {
    Page(topBar = {
        Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.widget_nas_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) }
    }) {
        Column(Modifier.padding(16.dp)) {
            Card {
                ListRow(
                    stringResource(R.string.widget_nas_follow),
                    sub = stringResource(R.string.widget_nas_follow_sub) + if (chosen == null) " · " + stringResource(R.string.nas_chosen) else "",
                    icon = { RowIcon(if (chosen == null) TitanIcons.check else TitanIcons.sync, tint = if (chosen == null) Titan.colors.accent else Titan.colors.muted) },
                    onClick = { onPick(null) },
                )
                for (n in list) {
                    val on = chosen == n.id
                    ListRow(
                        n.name,
                        sub = if (on) stringResource(R.string.nas_chosen) else null,
                        icon = { RowIcon(if (on) TitanIcons.check else TitanIcons.nodes, tint = if (on) Titan.colors.accent else Titan.colors.muted) },
                        divider = true,
                        onClick = { onPick(n.id) },
                    )
                }
            }
        }
    }
}
