package io.github.ra5on.titanos.account

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Die mitgelieferten Bilder (Picture.svelte). */
internal val BUILTIN_PICTURES = listOf("nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")

/** Ohne Wahl zeigt die Anmeldeseite einen Ausschnitt eines mitgelieferten Bildes, nach dem Namen (`fallbackPicture`). */
private val FALLBACK = listOf("nordlicht" to (45 to 30), "kueste" to (20 to 70), "wueste" to (60 to 60), "nebelwald" to (50 to 40), "bergsee" to (50 to 45), "weltraum" to (50 to 50))

internal fun fallbackOf(name: String): Pair<String, Pair<Int, Int>> {
    var hash = 0L
    for (cp in name.codePoints()) hash = (hash * 31 + cp) and 0xFFFFFFFFL
    return FALLBACK[(hash % FALLBACK.size).toInt()]
}

private fun decode(bytes: ByteArray, target: Int = 256): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/**
 * Bild (Picture.svelte): was die Anmeldeseite im Kreis dieses Kontos zeigt –
 * ein eigenes Foto (über die Dateiauswahl) oder ein mitgeliefertes Bild;
 * „Standard“ nimmt die Wahl zurück.
 */
@Composable
internal fun PictureRow(ctx: AccountCtx) {
    val context = LocalContext.current
    val tr = io.github.ra5on.titanos.ui.i18n.LocalTr.current
    val run = rememberRunner()
    var picture by remember { mutableStateOf<JsonObject?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val images = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) {
        runCatching { ctx.calls.picture() }.onSuccess { picture = it }.onFailure { loadError = true }
    }
    // Bilder vom NAS (die eigenen Fotos hinter der Anmeldung, die mitgelieferten neben der Oberfläche).
    LaunchedEffect(Unit) {
        for (id in BUILTIN_PICTURES) {
            if (id in images) continue
            val bytes = runCatching { ctx.calls.background(id) }.getOrNull() ?: continue
            withContext(Dispatchers.Default) { decode(bytes) }?.let { images[id] = it }
        }
    }
    val photo = picture.str("photo")
    LaunchedEffect(photo) {
        if (photo.isNotEmpty() && "photo:$photo" !in images) {
            val bytes = runCatching { ctx.calls.pictureBytes(photo) }.getOrNull()
            if (bytes != null) withContext(Dispatchers.Default) { decode(bytes) }?.let { images["photo:$photo"] = it }
        }
    }
    val builtin = picture.str("builtin")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            run.run(guard = GuardAction.Settings) {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: error("unreadable")
                picture = ctx.calls.picturePhoto(bytes, mime)
            }
        }
    }
    val summary = when {
        photo.isNotEmpty() -> t("account.picture.own")
        builtin in BUILTIN_PICTURES -> t("desktop.wallpaper.$builtin")
        else -> t("account.picture.none")
    }
    val shown: Pair<ImageBitmap?, BiasAlignment> = when {
        photo.isNotEmpty() -> images["photo:$photo"] to BiasAlignment(0f, 0f)
        builtin.isNotEmpty() -> images[builtin] to BiasAlignment(0f, 0f)
        else -> fallbackOf(ctx.name).let { (id, pos) -> images[id] to BiasAlignment(pos.first / 50f - 1f, pos.second / 50f - 1f) }
    }
    SettingRow(
        t("account.picture.title"),
        desc = t("account.picture.hint"),
        icon = WebIcons["image"],
        summary = if (picture != null || loadError) summary else null,
        id = "acc-picture",
        control = { Avatar(ctx.name, shown.first, shown.second) },
    ) {
        Block {
            FlowRow(
                Modifier.semantics { contentDescription = tr.t("account.picture.builtin") },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (id in BUILTIN_PICTURES) {
                    val label = t("desktop.wallpaper.$id")
                    val chosen = builtin == id
                    val c = Titan.colors
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .border(if (chosen) 3.dp else 1.dp, if (chosen) c.accent else c.border, CircleShape)
                            .background(c.chip)
                            .clickable(enabled = !run.running, role = Role.RadioButton) {
                                run.run(guard = GuardAction.Settings) { picture = ctx.calls.pictureBuiltin(id) }
                            }
                            .semantics { contentDescription = label; selected = chosen }
                            .padding(if (chosen) 4.dp else 0.dp),
                    ) {
                        val img = images[id]
                        if (img != null) Image(img, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                        else Text(label.take(1), style = TitanType.body, color = c.text, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        Block {
            Buttons {
                SmallButton(t("account.picture.upload"), { pick.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, enabled = !run.running)
                if (builtin.isNotEmpty() || photo.isNotEmpty()) {
                    SmallButton(t("account.picture.reset"), {
                        run.run(guard = GuardAction.Settings) { ctx.calls.pictureReset(); picture = JsonObject(emptyMap()) }
                    }, enabled = !run.running)
                }
            }
            RunnerState(run)
        }
    }
}

/** Der Kreis mit Bild und erstem Buchstaben des Namens (nur Schmuck: der Titel der Zeile nennt es). */
@Composable
private fun Avatar(name: String, image: ImageBitmap?, align: Alignment) {
    val c = Titan.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = align)
        Text(
            name.firstOrNull()?.uppercase().orEmpty(),
            style = TitanType.rowTitle.copy(fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f), blurRadius = 8f)),
            color = if (image != null) io.github.ra5on.titanos.ui.generated.IconSquareColors.on else c.onAccent,
        )
    }
}
