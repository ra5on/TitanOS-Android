package io.github.ra5on.titanos.agent.core

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Eine Bewegung für alles, was im Agenten gleitet (agent-motion.ts): eine
 * weiche, lange Verzögerung wie die Blätter am Handy. „Animationen: Aus“
 * oder „Bewegung reduzieren“ von Android (calm) geben 0 ms.
 */
object Motion {
    /** cubic-bezier(0.22, 1, 0.36, 1), die Kurve des Webs (EASE). */
    val Ease: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** quintOut von Svelte (sheetUp, slideIn). */
    val QuintOut: Easing = Easing { t -> val f = t - 1f; f * f * f * f * f + 1f }
    const val SHEET = 420
    const val PANEL = 240
    const val SLIDE = 400
    const val DIM = 300

    fun <T> spec(calm: Boolean, ms: Int, easing: Easing = QuintOut): FiniteAnimationSpec<T> =
        if (calm) snap() else tween(ms, easing = easing)
}

/** Ob sich der Agent ruhig verhält: Darstellung „ruhig“/Animationen nicht „voll“ oder weniger Bewegung im System. */
val LocalCalm = compositionLocalOf { false }

/** Bewegung reduzieren von Android: Animator-Dauer 0 („Animationen entfernen“). */
@Composable
fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
