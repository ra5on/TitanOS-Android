package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.ra5on.titanos.settings.pages.PageArgs

/**
 * Einstellungen › IPv64 gibt es nicht mehr (PLAN 79-2): wie im Web führen
 * alte Verweise ohne eigenen Inhalt zu Einstellungen › Dynamisches DNS, wo
 * IPv64 ein Anbieter bleibt. Hier wird nichts wieder eingebaut.
 */
@Composable
fun Ipv64Page(a: PageArgs) {
    LaunchedEffect(Unit) { a.openPage("ddns") }
}
