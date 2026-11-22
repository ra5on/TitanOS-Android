package io.github.ra5on.titanos.watch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.Camera
import io.github.ra5on.titanos.watch.data.CameraEvent
import io.github.ra5on.titanos.watch.data.isHidden
import io.github.ra5on.titanos.watch.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bild kleiner einlesen: die Uhr braucht kein volles Kamerabild. */
suspend fun decodeSmall(bytes: ByteArray, maxSide: Int = 480): Bitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
fun CamerasRoute(onCamera: (String) -> Unit) {
    val g = LocalContext.current.graph
    var cameras by remember { mutableStateOf<List<Camera>?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val api = g.api() ?: return@LaunchedEffect
        try {
            cameras = api.cameras()
        } catch (e: Exception) {
            if (e.isHidden()) cameras = emptyList() else problem = R.string.w_err_reach
        }
    }
    CamerasScreen(cameras, problem, onCamera)
}

@Composable
fun CamerasScreen(cameras: List<Camera>?, problem: Int?, onCamera: (String) -> Unit) {
    Page {
        item { Header(stringResource(R.string.w_cameras)) }
        problem?.let { item { Problem(stringResource(it)) } }
        val list = cameras
        when {
            list == null && problem == null -> item { Busy(stringResource(R.string.w_loading)) }
            list != null && list.isEmpty() -> item { WText(stringResource(R.string.w_cameras_none), center = true) }
        }
        list?.forEach { c -> item { RowButton(c.name, onClick = { onCamera(c.id) }, icon = TitanIcons.video) } }
    }
}

@Composable
fun CameraRoute(id: String) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var events by remember { mutableStateOf<List<CameraEvent>?>(null) }
    var shown by remember { mutableStateOf<Pair<CameraEvent, Bitmap?>?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    suspend fun load() {
        val api = g.api() ?: return
        try {
            name = api.cameras().firstOrNull { it.id == id }?.name.orEmpty()
            image = decodeSmall(api.snapshot(id))
            problem = null
        } catch (e: Exception) {
            problem = if (e.isHidden()) R.string.w_camera_err_rights else R.string.w_camera_err
        }
        events = runCatching { api.events(id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(id) { load() }
    CameraScreen(
        name = name,
        image = image,
        events = events,
        shown = shown,
        problem = problem,
        onReload = { scope.launch { load() } },
        onEvent = { e ->
            scope.launch {
                val bmp = if (e.hasSnapshot) runCatching { g.api()?.eventImage(e.id)?.let { decodeSmall(it) } }.getOrNull() else null
                shown = e to bmp
            }
        },
        onBackToLive = { shown = null },
    )
}

@Composable
fun CameraScreen(
    name: String,
    image: Bitmap?,
    events: List<CameraEvent>?,
    shown: Pair<CameraEvent, Bitmap?>?,
    problem: Int?,
    onReload: () -> Unit,
    onEvent: (CameraEvent) -> Unit,
    onBackToLive: () -> Unit,
) {
    val title = name.ifBlank { stringResource(R.string.w_camera) }
    val picture = stringResource(R.string.w_camera_picture, title)
    Page {
        item { Header(title) }
        problem?.let { item { Problem(stringResource(it)) } }
        if (shown != null) {
            item { WText(eventLabel(shown.first), center = true) }
            shown.second?.let { bmp -> item { Picture(bmp, eventLabel(shown.first)) } }
            item { RowButton(stringResource(R.string.w_camera_back_live), onClick = onBackToLive, icon = TitanIcons.video) }
        } else {
            image?.let { bmp -> item { Picture(bmp, picture) } }
            if (image == null && problem == null) item { Busy(stringResource(R.string.w_loading)) }
            item { RowButton(stringResource(R.string.w_reload), onClick = onReload, icon = TitanIcons.restart) }
        }
        if (!events.isNullOrEmpty()) {
            item { Header(stringResource(R.string.w_camera_events)) }
            events.forEach { e ->
                item { RowButton(eventLabel(e), sub = time((e.start * 1000).toLong()), icon = TitanIcons.image, onClick = { onEvent(e) }) }
            }
        } else if (events != null) {
            item { WText(stringResource(R.string.w_camera_no_events), center = true, color = WatchColors.muted) }
        }
    }
}

@Composable
private fun Picture(bmp: Bitmap, description: String) {
    Image(
        bmp.asImageBitmap(),
        contentDescription = description,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun eventLabel(e: CameraEvent): String {
    val what = when (e.label) {
        "person" -> stringResource(R.string.w_label_person)
        "car" -> stringResource(R.string.w_label_car)
        "dog", "cat", "bird" -> stringResource(R.string.w_label_animal)
        "package" -> stringResource(R.string.w_label_package)
        else -> e.label
    }
    return if (e.subLabel.isNotBlank()) "$what · ${e.subLabel}" else what
}
