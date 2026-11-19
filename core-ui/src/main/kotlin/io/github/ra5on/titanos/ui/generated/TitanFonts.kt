// Erzeugt von TitanOS/web/scripts/android-theme.mjs – nicht von Hand ändern.
// Quellen: web/src/app.css, web/src/lib/theme/themes.ts, web/src/lib/desktop/DesktopIcon.svelte.
package io.github.ra5on.titanos.ui.generated

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.ra5on.titanos.ui.R

/**
 * Schrift wie im Web: --font-sans „IBM Plex Sans“, --font-mono „IBM Plex Mono“, dieselben
 * Schnitte wie web/src/routes/+layout.svelte (@fontsource 5.3.0, OFL-1.1).
 */
object TitanFonts {
    val sans = FontFamily(
        Font(R.font.ibm_plex_sans_400, FontWeight(400)),
        Font(R.font.ibm_plex_sans_500, FontWeight(500)),
        Font(R.font.ibm_plex_sans_600, FontWeight(600)),
    )
    val mono = FontFamily(
        Font(R.font.ibm_plex_mono_400, FontWeight(400)),
        Font(R.font.ibm_plex_mono_500, FontWeight(500)),
    )
}
