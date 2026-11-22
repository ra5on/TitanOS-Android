package io.github.ra5on.titanos.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * Haltung eines Faltbaren (Inhaber 21.11.2026): Tisch (halb aufgeklappt,
 * Falz waagerecht: oben Bild, unten Steuerung) und Buch (Falz senkrecht:
 * links Liste, rechts Inhalt). Flach heißt ganz offen, zu oder kein Faltbares.
 */
enum class Posture { Flat, Book, Tabletop }

/** Die Haltung und wo der Falz liegt (Pixel im Fenster der Activity). */
data class Fold(val posture: Posture, val hinge: IntRect = IntRect.Zero) {
    companion object {
        val None = Fold(Posture.Flat)
    }
}

/** Ein Falz, wie androidx.window ihn meldet, ohne Android-Typen (für Tests). */
data class FoldFeature(val halfOpened: Boolean, val horizontal: Boolean, val bounds: IntRect)

/** Die Haltung zu den gemeldeten Falzen: nur ein halb aufgeklappter zählt. */
fun foldOf(features: List<FoldFeature>): Fold {
    val f = features.firstOrNull { it.halfOpened } ?: return Fold.None
    return Fold(if (f.horizontal) Posture.Tabletop else Posture.Book, f.bounds)
}

/** Übersetzt, was [WindowInfoTracker] meldet. */
fun foldOf(info: WindowLayoutInfo): Fold = foldOf(
    info.displayFeatures.filterIsInstance<FoldingFeature>().map {
        FoldFeature(
            halfOpened = it.state == FoldingFeature.State.HALF_OPENED,
            horizontal = it.orientation == FoldingFeature.Orientation.HORIZONTAL,
            bounds = it.bounds.let { b -> IntRect(b.left, b.top, b.right, b.bottom) },
        )
    },
)

/** Für Bildtests: eine feste Haltung statt der des Geräts. */
val LocalFoldOverride = staticCompositionLocalOf<Fold?> { null }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Die Haltung des Geräts jetzt; ändert sich beim Auf- und Zuklappen. */
@Composable
fun currentFold(): Fold {
    LocalFoldOverride.current?.let { return it }
    val activity = LocalContext.current.activity() ?: return Fold.None
    var fold by remember(activity) { mutableStateOf(Fold.None) }
    LaunchedEffect(activity) {
        runCatching {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { fold = foldOf(it) }
        }
    }
    return fold
}

/**
 * Gemeinsamer Baustein für Faltbare: [first] und [second] stehen in
 * Tischhaltung über und unter dem Falz ([tabletop]), in Buchhaltung links
 * und rechts davon ([book]); sonst ordnet [flat] sie wie bisher an. Beide
 * Teile sind bewegliche Inhalte: beim Auf- und Zuklappen bleiben Verbindung,
 * Bild und Eingaben erhalten (kein neuer Aufbau). Der Falz zählt nur, wenn er
 * durch diese Fläche läuft (ein Fenster des Arbeitsplatzes daneben bleibt flach).
 */
@Composable
fun FoldPanes(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tabletop: Boolean = true,
    book: Boolean = true,
    flat: @Composable (first: @Composable () -> Unit, second: @Composable () -> Unit) -> Unit,
) {
    val fold = currentFold()
    val firstNow by rememberUpdatedState(first)
    val secondNow by rememberUpdatedState(second)
    val a = remember { movableContentOf { firstNow() } }
    val b = remember { movableContentOf { secondNow() } }
    val density = LocalDensity.current
    // Bis die Lage bekannt ist: das ganze Fenster (der häufigste Fall), damit das erste Bild schon stimmt.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    var area by remember { mutableStateOf<Rect?>(with(density) { Rect(0f, 0f, config.screenWidthDp.dp.toPx(), config.screenHeightDp.dp.toPx()) }) }
    Box(modifier.onGloballyPositioned { area = it.boundsInWindow() }) {
        val box = area
        val hinge = fold.hinge
        val crossesY = box != null && hinge.top >= box.top && hinge.bottom <= box.bottom && hinge.top > box.top
        val crossesX = box != null && hinge.left >= box.left && hinge.right <= box.right && hinge.left > box.left
        when {
            fold.posture == Posture.Tabletop && tabletop && crossesY -> Column(Modifier.fillMaxSize()) {
                with(density) {
                    Box(Modifier.fillMaxWidth().height((hinge.top - box!!.top).toDp())) { a() }
                    Spacer(Modifier.height(hinge.height.toDp()))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { b() }
            }
            fold.posture == Posture.Book && book && crossesX -> Row(Modifier.fillMaxSize()) {
                with(density) {
                    Box(Modifier.fillMaxHeight().width((hinge.left - box!!.left).toDp())) { a() }
                    Spacer(Modifier.width(hinge.width.toDp()))
                }
                Box(Modifier.fillMaxHeight().weight(1f)) { b() }
            }
            else -> flat(a, b)
        }
    }
}
