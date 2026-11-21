package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val PictureBg = Color(0xFF0B0E12)
private val OverlayBg = Color(0xE6111418)

/** Ein dunkles Schild im Bild (Name, Standbild, Marke); weiß auf fast schwarz, Kontrast über 4,5:1. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, bg: Color = OverlayBg, fg: Color = Color.White, bold: Boolean = false) {
    Text(
        text,
        style = TitanType.small.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Ein Knopf im Bild (Ton, Schwenken, groß); 44 dp, beschriftet für Bildschirmleser. */
@Composable
fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, on: Boolean = false, onWord: String? = null) {
    val c = Titan.colors
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent else OverlayBg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (onWord != null) stateDescription = onWord
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (on) c.onAccent else Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Eine Kamera der Live-Ansicht (LiveTile.svelte): Bild, Name im Bild, was sie
 * gerade sieht, der rote Punkt während sie aufnimmt, Knöpfe für Ton,
 * Schwenken und groß. Gegensprechen fehlt (siehe Bericht: kein WebRTC).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveTile(
    camera: Camera,
    running: Boolean,
    prefs: NvrPrefs,
    activity: Activity?,
    api: NvrApi?,
    modifier: Modifier = Modifier,
    arranging: Boolean = false,
    solo: Boolean = false,
    small: Boolean = false,
    ptzStart: Boolean = false,
    onSolo: (() -> Unit)? = null,
    onPtz: (() -> Unit)? = null,
) {
    val loc = locale()
    val condition = camera.condition(running)
    var shown by remember(camera.id) { mutableStateOf(LiveShown(LiveMode.None)) }
    var sound by remember(camera.id) { mutableStateOf(false) }
    var ptzOpen by remember(camera.id) { mutableStateOf(ptzStart) }
    val recording = running && camera.enabled && camera.state.online && camera.record != null && camera.record.mode != "off"
    val seen = if (running && camera.enabled) activity?.objects.orEmpty() else emptyList()
    val offlineText = camera.state.offlineSince?.let { t("nvr.camera.offlineSince", "time" to sinceText(it, loc)) } ?: t("nvr.camera.stateOffline")
    val labels = !(ptzOpen && camera.ptz)
    val onWord = stringResource(io.github.ra5on.titanos.compute.R.string.compute_on)
    val offWord = stringResource(io.github.ra5on.titanos.compute.R.string.compute_off)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(PictureBg)) {
        val roomForPad = maxHeight >= 210.dp
        if (condition != Condition.Off) {
            LocalNvrMedia.current.Live(
                camera.id, t("nvr.live.videoOf", "name" to camera.name), small, sound, solo, if (solo) 1080 else 480,
                Modifier.fillMaxSize(),
            ) { shown = it }
        }
        val middle = when {
            condition == Condition.Off -> t("nvr.camera.stateOff")
            condition == Condition.Offline && shown.mode == LiveMode.None -> offlineText
            shown.mode == LiveMode.None -> when {
                shown.reason == "forbidden" -> t("nvr.live.forbidden")
                shown.reason.isNotEmpty() && shown.reason != "nvr_no_picture" -> t(keyOr("error.${shown.reason}", "nvr.live.noPicture"))
                shown.stillFailed -> t("nvr.live.noPicture")
                else -> t("nvr.live.connecting")
            }
            else -> null
        }
        if (middle != null) PictureNote(middle, Modifier.align(Alignment.Center))
        if (labels) {
            FlowRow(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (condition == Condition.Offline && shown.mode != LiveMode.None) Badge(offlineText)
                if (prefs.marks) for (label in seen) Badge(labelName(label), bg = Color(0xFF0E6B45), bold = true)
                Spacer(Modifier.weight(1f))
                if (shown.mode == LiveMode.Still && condition == Condition.Online && !arranging) Badge(t("nvr.live.still"))
                if (prefs.recDot && recording && !arranging) {
                    val rec = t("nvr.live.recording")
                    Box(
                        Modifier.padding(top = 4.dp).size(14.dp).clip(CircleShape).background(Color(0xFFE5483B)).border(2.dp, Color.White, CircleShape)
                            .semantics { contentDescription = rec },
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ptzOpen && camera.ptz && !arranging && api != null) PtzPad(api, camera.id, camera.name)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                if (prefs.names && labels) Badge(camera.name)
                Spacer(Modifier.weight(1f))
                if (!arranging && condition != Condition.Off) {
                    if (camera.audio.enabled) ToolButton(
                        if (sound) NvrIcons.volume else NvrIcons.volumeOff,
                        t("nvr.live.sound", "name" to camera.name),
                        { sound = !sound },
                        on = sound,
                        onWord = if (sound) onWord else offWord,
                    )
                    if (camera.ptz) ToolButton(TitanIcons.move, t("nvr.ptz.of", "name" to camera.name), {
                        if (!ptzOpen && !roomForPad && onPtz != null) onPtz() else ptzOpen = !ptzOpen
                    }, on = ptzOpen, onWord = if (ptzOpen) onWord else offWord)
                    if (onSolo != null) ToolButton(
                        if (solo) TitanIcons.x else NvrIcons.maximize,
                        t(if (solo) "nvr.live.soloOff" else "nvr.live.solo", "name" to camera.name),
                        onSolo,
                    )
                }
            }
        }
    }
}

/**
 * Schwenken und Zoomen (PtzPad.svelte): eine Bewegung läuft, solange ihr
 * Knopf gehalten wird; alle 2 s erneut (das NAS hält nach 5 s Stille an),
 * „stop“ beim Loslassen, im Hintergrund und beim Verschwinden. Höchstens
 * zehn Befehle je Sekunde: 100 ms Abstand. Ein abgelehntes „stop“ wird
 * bis zu viermal wiederholt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PtzPad(api: NvrApi, camera: String, name: String) {
    var info by remember(camera) { mutableStateOf<PtzInfo?>(null) }
    var error by remember(camera) { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(camera) {
        info = runCatching { api.ptz(camera) }.getOrElse { PtzInfo(false) }
    }
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }
    var lastSent by remember { mutableStateOf(0L) }
    var moving by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf<Job?>(null) }

    fun send(action: String, preset: String? = null, tries: Int = 1) {
        scope.launch {
            lock.withLock {
                val wait = lastSent + 100 - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastSent = System.currentTimeMillis()
                try {
                    api.ptzCommand(camera, action, preset)
                    error = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiException && e.code == "rate_limited") {
                        if (tries < 4 && action == "stop") {
                            scope.launch {
                                delay(150L * tries)
                                send(action, preset, tries + 1)
                            }
                        }
                    } else {
                        error = e
                    }
                }
            }
        }
    }

    fun stop(tell: Boolean = true) {
        repeat?.cancel()
        repeat = null
        if (moving.isNotEmpty() && tell) send("stop")
        moving = ""
    }

    fun start(action: String) {
        stop(false)
        moving = action
        send(action)
        repeat = scope.launch {
            while (true) {
                delay(2000)
                send(action)
            }
        }
    }
    val stopNow by rememberUpdatedState({ stop() })
    LifecycleResumeEffect(camera) { onPauseOrDispose { stopNow() } }
    DisposableEffect(camera) { onDispose { stopNow() } }

    val i = info ?: return
    if (!i.supported) return
    val zoom = "zoom" in i.features.orEmpty()

    @Composable
    fun hold(action: String, label: String, icon: ImageVector?, text: String = "") {
        val c = Titan.colors
        val on = moving == action
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent else OverlayBg)
                .pointerInput(action) {
                    awaitEachGesture {
                        awaitFirstDown()
                        start(action)
                        waitForUpOrCancellation()
                        stop()
                    }
                }
                .onKeyEvent { e ->
                    val k = e.key == Key.Enter || e.key == Key.Spacebar || e.key == Key.NumPadEnter
                    if (!k) return@onKeyEvent false
                    if (e.type == KeyEventType.KeyDown && moving != action) start(action)
                    if (e.type == KeyEventType.KeyUp) stop()
                    true
                }
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    selected = on
                },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = if (on) c.onAccent else Color.White, modifier = Modifier.size(22.dp))
            else Text(text, color = if (on) c.onAccent else Color.White, style = TitanType.topTitle)
        }
    }

    val groupLabel = t("nvr.ptz.of", "name" to name)
    Column(
        Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(8.dp)
            .semantics { contentDescription = groupLabel },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hold("move_up", t("nvr.ptz.up"), NvrIcons.up)
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    hold("move_left", t("nvr.ptz.left"), NvrIcons.left)
                    hold("move_right", t("nvr.ptz.right"), NvrIcons.right)
                }
                hold("move_down", t("nvr.ptz.down"), NvrIcons.downArrow)
            }
            if (zoom) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hold("zoom_in", t("nvr.ptz.zoomIn"), null, "+")
                hold("zoom_out", t("nvr.ptz.zoomOut"), null, "−")
            }
        }
        val presets = i.presets.orEmpty()
        if (presets.isNotEmpty()) {
            Box(Modifier.widthIn(max = 260.dp)) {
                Choice(t("nvr.ptz.presets"), listOf("" to t("nvr.ptz.presets")) + presets.map { it to it }, "", { if (it.isNotEmpty()) send("preset", it) })
            }
        }
        error?.let { Text(errorText(it), color = Color.White, style = TitanType.small) }
    }
}

/** Ein Ereignis als kleine Karte (EventCard.svelte): Bild, was, wo, wann; Gesicht oder Kennzeichen dazu. */
@Composable
fun EventCard(event: NvrEvent, camera: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val loc = locale()
    val extra = listOfNotNull(
        event.subLabel.ifEmpty { null },
        event.plate.takeIf { it.isNotEmpty() && it != event.subLabel },
        if (event.end == null) t("nvr.events.running") else null,
    ).joinToString(" · ")
    val unseen = t("nvr.events.unseen")
    val title = "${labelName(event.label)} · $camera"
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { if (!event.reviewed) stateDescription = unseen }
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NvrPicture(NvrApi.thumbnailPath(event.id), null, Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = c.text, modifier = Modifier.weight(1f, fill = false))
                if (!event.reviewed) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            }
            Text(dayClock(event.start, loc) + if (extra.isNotEmpty()) " · $extra" else "", style = TitanType.rowSub, color = c.muted)
            if (event.kept) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(TitanIcons.pin, null, tint = c.text, modifier = Modifier.size(14.dp))
                Text(t("nvr.events.keptBadge"), style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
        }
    }
}
