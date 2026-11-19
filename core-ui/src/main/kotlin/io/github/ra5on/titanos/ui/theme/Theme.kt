package io.github.ra5on.titanos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.ui.generated.TitanColors
import io.github.ra5on.titanos.ui.generated.TitanFonts

val LocalTitanColors = staticCompositionLocalOf { TitanColors.Graphit }

/** Schriftstufen der Entwürfe (Entwuerfe/116-android/lib.mjs, 1 px = 1 sp). Das Web hat höchstens 600. */
object TitanType {
    private val base = TextStyle(fontFamily = TitanFonts.sans, fontSize = 15.sp, lineHeight = 20.sp)
    val body = base
    val bodyMuted = base
    val rowTitle = base.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val rowSub = base.copy(fontSize = 13.5.sp, lineHeight = 18.sp)
    val small = base.copy(fontSize = 13.sp, lineHeight = 17.sp)
    val section = base.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em)
    val topTitle = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val big = base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val title = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val state = base.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
    val button = base.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val nav = base.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val appLabel = base.copy(fontSize = 13.sp, lineHeight = 15.6.sp, fontWeight = FontWeight.Medium, hyphens = Hyphens.Auto)
    val mono = TextStyle(fontFamily = TitanFonts.mono, fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em)
    val code = TextStyle(fontFamily = TitanFonts.mono, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.2.em)
}

/** TitanOS-Theme: Graphit (dunkel) oder Hell, folgt dem System. */
@Composable
fun TitanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) TitanColors.Graphit else TitanColors.Hell
    val material = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.muted, outline = c.border,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface,
            error = c.statusError,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.muted, outline = c.border,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface,
            error = c.statusError,
        )
    }
    CompositionLocalProvider(LocalTitanColors provides c) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

object Titan {
    val colors: TitanColors
        @Composable get() = LocalTitanColors.current
}
