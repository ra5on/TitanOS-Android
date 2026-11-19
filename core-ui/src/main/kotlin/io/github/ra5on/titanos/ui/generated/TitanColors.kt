// Erzeugt von TitanOS/scripts/android-theme.mjs – nicht von Hand ändern.
// Quellen: web/src/app.css, web/src/lib/theme/themes.ts, web/src/lib/desktop/DesktopIcon.svelte.
package io.github.ra5on.titanos.ui.generated

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Theme-Farben wie die CSS-Variablen der Weboberfläche (--bg, --surface, …). */
@Immutable
class TitanColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val header: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val track: Color,
    val chip: Color,
    val accent: Color,
    val onAccent: Color,
    val warnBg: Color,
    val warnBorder: Color,
    val warnText: Color,
    val errorBg: Color,
    val errorBorder: Color,
    val errorText: Color,
    val okBg: Color,
    val okBorder: Color,
    val okText: Color,
) {
    /** Statusfarben (in jedem Theme gleich). Nie ohne Text verwenden. */
    val statusOk: Color get() = StatusColors.ok
    val statusWarn: Color get() = StatusColors.warn
    val statusError: Color get() = StatusColors.error
    val statusInactive: Color get() = StatusColors.inactive

    companion object {
    val Graphit = TitanColors(
        dark = true,
        bg = Color(0xFF0E1114),
        surface = Color(0xFF161A1F),
        header = Color(0xFF12161A),
        border = Color(0xFF232A31),
        text = Color(0xFFE6E9ED),
        muted = Color(0xFF9AA3AD),
        track = Color(0xFF232A31),
        chip = Color(0xFF1E252C),
        accent = Color(0xFF6CB6FF),
        onAccent = Color(0xFF0B1622),
        warnBg = Color(0xFF2A2312),
        warnBorder = Color(0xFF5A4720),
        warnText = Color(0xFFF6D79A),
        errorBg = Color(0xFF2A1512),
        errorBorder = Color(0xFF5A2A20),
        errorText = Color(0xFFF6B8A4),
        okBg = Color(0xFF12281E),
        okBorder = Color(0xFF245A40),
        okText = Color(0xFF9BE3BE),
    )

    val Hell = TitanColors(
        dark = false,
        bg = Color(0xFFF3F4F6),
        surface = Color(0xFFFFFFFF),
        header = Color(0xFFFFFFFF),
        border = Color(0xFFDCE0E5),
        text = Color(0xFF14181D),
        muted = Color(0xFF56606B),
        track = Color(0xFFE3E7EC),
        chip = Color(0xFFEAEDF1),
        accent = Color(0xFF1F6FC4),
        onAccent = Color(0xFFFFFFFF),
        warnBg = Color(0xFFFFF4D6),
        warnBorder = Color(0xFFE2C46C),
        warnText = Color(0xFF6B4E00),
        errorBg = Color(0xFFFDE8E2),
        errorBorder = Color(0xFFE8A898),
        errorText = Color(0xFF8C2A12),
        okBg = Color(0xFFE3F6EC),
        okBorder = Color(0xFF9AD4B4),
        okText = Color(0xFF17603C),
    )
    }
}

object StatusColors {
    val ok = Color(0xFF4CC38A)
    val warn = Color(0xFFF0B54A)
    val error = Color(0xFFF28C6B)
    val inactive = Color(0xFF6B7580)
    val onWarn = Color(0xFF1A1406)
}

/** Farbige Symbol-Quadrate der Systemsteuerung (--icon-square-*), in jedem Theme gleich. */
object IconSquareColors {
    val blue = Color(0xFF2563EB)
    val violet = Color(0xFF7C3AED)
    val teal = Color(0xFF0F766E)
    val cyan = Color(0xFF0E7490)
    val green = Color(0xFF15803D)
    val orange = Color(0xFFC2410C)
    val amber = Color(0xFFB45309)
    val red = Color(0xFFDC2626)
    val pink = Color(0xFFBE185D)
    val slate = Color(0xFF475569)
    val on = Color(0xFFFFFFFF)
}

/** QR-Code immer dunkel auf hell. */
object QrColors {
    val light = Color(0xFFFFFFFF)
    val dark = Color(0xFF000000)
}
