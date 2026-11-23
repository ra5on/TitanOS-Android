package io.github.ra5on.titanos.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** „Animationen entfernen“ in den Bedienungshilfen bzw. Animationsskala 0: dann nichts bewegen. */
fun reduceMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** Wie [reduceMotion] für Compose (einmal je Bildschirm gelesen). */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember { reduceMotion(context) }
}
