package io.github.ra5on.titanos.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Strichsymbole der Entwürfe (24er Raster, Strich 1,8, runde Enden), von Hand
 * übernommen aus Entwuerfe/116-android/lib.mjs. Farbe über `Icon(tint = …)`.
 */
object TitanIcons {
    private fun line(name: String, d: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val back: ImageVector by lazy { line("back", "m15 6-6 6 6 6") }
    val chev: ImageVector by lazy { line("chev", "m9 6 6 6-6 6") }
    val down: ImageVector by lazy { line("down", "m6 9 6 6 6-6") }
    val plus: ImageVector by lazy { line("plus", "M12 5v14M5 12h14") }
    val search: ImageVector by lazy { line("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14ZM20 20l-4.2-4.2") }
    val bell: ImageVector by lazy { line("bell", "M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15ZM10 20a2 2 0 0 0 4 0") }
    val more: ImageVector by lazy { line("more", "M5 12h.01M12 12h.01M19 12h.01") }
    val menu: ImageVector by lazy { line("menu", "M4 7h16M4 12h16M4 17h16") }
    val home: ImageVector by lazy { line("home", "M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1Z") }
    val folder: ImageVector by lazy { line("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z") }
    val photo: ImageVector by lazy { line("photo", "M4 6h16v12H4ZM4 15l4-4 4 4 3-3 5 5M15.5 9.5h.01") }
    val grid: ImageVector by lazy { line("grid", "M4 4h6v6H4ZM14 4h6v6h-6ZM4 14h6v6H4ZM14 14h6v6h-6Z") }
    val file: ImageVector by lazy { line("file", "M7 3h7l5 5v13H7ZM14 3v5h5") }
    val pdf: ImageVector by lazy { line("pdf", "M7 3h7l5 5v13H7ZM14 3v5h5M9.5 15.5h5M9.5 12.5h5") }
    val upload: ImageVector by lazy { line("upload", "M12 16V5M7 10l5-5 5 5M5 19h14") }
    val download: ImageVector by lazy { line("download", "M12 5v11M7 11l5 5 5-5M5 19h14") }
    val share: ImageVector by lazy { line("share", "M12 4v11M8 8l4-4 4 4M6 12v7h12v-7") }
    val cloud: ImageVector by lazy { line("cloud", "M7 18a4 4 0 0 1-.5-8 6 6 0 0 1 11.5 1.5A3.5 3.5 0 0 1 17.5 18Z") }
    val cloudok: ImageVector by lazy { line("cloudok", "M7 18a4 4 0 0 1-.5-8 6 6 0 0 1 11.5 1.5A3.5 3.5 0 0 1 17.5 18ZM9.5 13.5l2 2 3.5-3.5") }
    val pin: ImageVector by lazy { line("pin", "M12 21s-6-5.5-6-11a6 6 0 1 1 12 0c0 5.5-6 11-6 11ZM12 8v4") }
    val offline: ImageVector by lazy { line("offline", "M12 4v10M8 10l4 4 4-4M5 18h14M5 21h14") }
    val trash: ImageVector by lazy { line("trash", "M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13") }
    val edit: ImageVector by lazy { line("edit", "M5 19h4L19 9l-4-4L5 15ZM13.5 6.5l4 4") }
    val move: ImageVector by lazy { line("move", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2ZM10 13h5M13 10.5 15.5 13 13 15.5") }
    val wifi: ImageVector by lazy { line("wifi", "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0M12 19.5h.01") }
    val battery: ImageVector by lazy { line("battery", "M3 8h15v8H3ZM20.5 10.5v3") }
    val bolt: ImageVector by lazy { line("bolt", "M13 3 5 14h6l-1 7 8-11h-6Z") }
    val cell: ImageVector by lazy { line("cell", "M4 20h2v-4H4ZM9 20h2v-8H9ZM14 20h2V8h-2ZM19 20h2V4h-2Z") }
    val play: ImageVector by lazy { line("play", "M8 5v14l11-7Z") }
    val stop: ImageVector by lazy { line("stop", "M7 7h10v10H7Z") }
    val restart: ImageVector by lazy { line("restart", "M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4") }
    val check: ImageVector by lazy { line("check", "m5 12 4.5 4.5L19 7") }
    val x: ImageVector by lazy { line("x", "M6 6l12 12M18 6 6 18") }
    val qr: ImageVector by lazy { line("qr", "M4 4h6v6H4ZM14 4h6v6h-6ZM4 14h6v6H4ZM14 14h2v2h-2ZM18 14h2v2h-2ZM14 18h2v2h-2ZM18 18h2v2h-2ZM16 16h2v2h-2Z") }
    val lock: ImageVector by lazy { line("lock", "M6 11h12v9H6ZM8.5 11V8a3.5 3.5 0 0 1 7 0v3") }
    val shield: ImageVector by lazy { line("shield", "M12 3 5 6v5c0 4.6 3 8 7 9 4-1 7-4.4 7-9V6Z") }
    val phone: ImageVector by lazy { line("phone", "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1ZM11 18h2") }
    val user: ImageVector by lazy { line("user", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM5 20a7 7 0 0 1 14 0") }
    val cal: ImageVector by lazy { line("cal", "M4 6h16v14H4ZM4 10h16M8 3v4M16 3v4") }
    val mail: ImageVector by lazy { line("mail", "M3 6h18v12H3ZM3.5 7l8.5 6 8.5-6") }
    val sync: ImageVector by lazy { line("sync", "M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5") }
    val info: ImageVector by lazy { line("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 11v5M12 8h.01") }
    val warn: ImageVector by lazy { line("warn", "M12 4 2.5 20h19ZM12 10v4M12 17h.01") }
    val gear: ImageVector by lazy { line("gear", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM19.4 13.5l1.6 1.2-2 3.4-1.9-.7a7 7 0 0 1-2.1 1.2L14.7 21h-4l-.3-2.1a7 7 0 0 1-2.1-1.2l-1.9.7-2-3.4 1.6-1.2a7 7 0 0 1 0-2.6L4.4 9.9l2-3.4 1.9.7a7 7 0 0 1 2.1-1.2L10.7 3h4l.3 2.1a7 7 0 0 1 2.1 1.2l1.9-.7 2 3.4-1.6 1.2a7 7 0 0 1 0 2.6Z") }
    val chip: ImageVector by lazy { line("chip", "M7 7h10v10H7ZM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4") }
    val globe: ImageVector by lazy { line("globe", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM3 12h18M12 3c2.5 2.6 3.7 5.6 3.7 9s-1.2 6.4-3.7 9c-2.5-2.6-3.7-5.6-3.7-9S9.5 5.6 12 3Z") }
    val box: ImageVector by lazy { line("box", "M4 7v10l8 4 8-4V7l-8-4ZM4 7l8 4 8-4M12 11v10") }
    val bulb: ImageVector by lazy { line("bulb", "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3Z") }
    val nodes: ImageVector by lazy { line("nodes", "M12 5v5M7 18l4-6M17 18l-4-6M12 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM6 17a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM18 17a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z") }
    val archive: ImageVector by lazy { line("archive", "M4 5h16v4H4ZM5 9v10h14V9M10 13h4") }
    val send: ImageVector by lazy { line("send", "M4 12 20 4l-6 16-3-7Z") }
    val mic: ImageVector by lazy { line("mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3ZM6 11a6 6 0 0 0 12 0M12 17v4") }
    val sparkle: ImageVector by lazy { line("sparkle", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8Z") }
    val video: ImageVector by lazy { line("video", "M4 7h11v10H4ZM15 10l5-3v10l-5-3") }
    val image: ImageVector by lazy { line("image", "M4 5h16v14H4ZM4 15l4-4 4 4 3-3 5 5") }
    val hdd: ImageVector by lazy { line("hdd", "M4 5h16v6H4ZM4 13h16v6H4ZM16 8h.01M16 16h.01") }
    val docker: ImageVector by lazy { line("docker", "M3 12h17c-.5 4-3.5 7-9 7-4.5 0-7-2.5-8-7ZM6 9h3v3H6ZM9.5 9h3v3h-3ZM13 9h3v3h-3ZM9.5 5.5h3v3h-3Z") }
    val monitor: ImageVector by lazy { line("monitor", "M3 4h18v12H3ZM8 20h8M12 16v4") }
    val key: ImageVector by lazy { line("key", "M14 10a4 4 0 1 0-3.5 4L8 16.5V19h2.5v-2H13v-2.5l1.5-1.5A4 4 0 0 0 14 10ZM15.5 8.5h.01") }
    val update: ImageVector by lazy { line("update", "M12 4v9M8 9l4 4 4-4M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3") }
}
