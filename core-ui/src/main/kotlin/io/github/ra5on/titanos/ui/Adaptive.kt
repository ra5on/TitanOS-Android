package io.github.ra5on.titanos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Breitenstufen wie die Weboberfläche (Inhaber 21.11.2026: auf großen
 * Bildschirmen „wie das Web am PC“). Das Web schaltet bei 760 px auf das
 * Handy und ab 900 px auf die PC-Aufteilung; die App rechnet in dp der
 * aktuellen Fenstergröße, damit Desktop-Modus, geteilter Bildschirm und
 * aufgeklapptes Faltbares stimmen. Jede Fenstergröße ist erlaubt; Seiten
 * passen sich live an (kein Neustart).
 */
enum class Width {
    /** Bis 760 dp: Handy-Gestaltung (Ebenen, Leiste unten). */
    Phone,

    /** 761–899 dp: Liste und Inhalt nebeneinander, Leiste unten bleibt. */
    Split,

    /** 900–1279 dp: PC-Aufteilung des Webs mit Seitenleiste. */
    Wide,

    /** Ab 1280 dp: wie Wide; hier zeigt die TitanOS-App ab Werk den Arbeitsplatz (Einstellung Automatisch/Immer/Nie). */
    Desktop,
    ;

    val twoPanes: Boolean get() = this != Phone
    val sidebar: Boolean get() = this == Wide || this == Desktop
}

/** Die Breitenstufe des Fensters, in dem diese Seite gerade steht. */
@Composable
@ReadOnlyComposable
fun currentWidth(): Width = widthOf(LocalConfiguration.current.screenWidthDp)

fun widthOf(dp: Int): Width = when {
    dp <= 760 -> Width.Phone
    dp < 900 -> Width.Split
    dp < 1280 -> Width.Wide
    else -> Width.Desktop
}

/**
 * Rechtsklick (Maus im Desktop-Modus) oder Stift-Seitentaste: öffnet dasselbe
 * Menü, das am Handy langes Drücken öffnet. Am Handy ohne Maus passiert nichts.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (currentEvent.buttons.isSecondaryPressed) {
            down.consume()
            action()
        }
    }
}

/**
 * Breite des längsten Wortes in [texts] (Pixel) bei [style] und der eingestellten
 * Schriftgröße. Damit entscheiden Raster und Kartenreihen, ob sie nebeneinander
 * passen, ohne ein Wort mitten durch zu trennen (Inhaber 21.11.2026: „Ganze Wörter“).
 * Bindestrich-Wörter („Foto-Sicherung“) zählen als ein Wort: Android bricht sie
 * sonst irgendwo im Wort statt am Bindestrich.
 */
@Composable
fun rememberWidestWord(texts: List<String>, style: androidx.compose.ui.text.TextStyle): Int {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return androidx.compose.runtime.remember(texts, style, density) {
        texts.flatMap { it.split(' ', ' ', '\n') }
            .filter { it.isNotEmpty() }
            .maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
    }
}
