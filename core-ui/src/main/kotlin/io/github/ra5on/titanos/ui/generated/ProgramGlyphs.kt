// Erzeugt von TitanOS/scripts/android-theme.mjs – nicht von Hand ändern.
// Quellen: web/src/app.css, web/src/lib/theme/themes.ts, web/src/lib/desktop/DesktopIcon.svelte.
package io.github.ra5on.titanos.ui.generated

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Programmsymbole wie am Arbeitsplatz (GLYPHS in DesktopIcon.svelte): weiß auf
 * einem Verlauf, Einzelheiten in der dunkleren Verlaufsfarbe [c].
 */
object ProgramGlyphs {
    val names: List<String> = listOf("files", "docker", "vm", "lxc", "storage", "shares", "backup", "store", "agent", "monitor", "logs", "terminal", "shield", "settings", "nvr", "calendar", "contacts", "mail", "users", "planner", "account", "overview", "screen", "photos", "bell")

    fun vector(name: String, c: Color): ImageVector? = when (name) {
        "files" -> files(c)
        "docker" -> docker(c)
        "vm" -> vm(c)
        "lxc" -> lxc(c)
        "storage" -> storage(c)
        "shares" -> shares(c)
        "backup" -> backup(c)
        "store" -> store(c)
        "agent" -> agent(c)
        "monitor" -> monitor(c)
        "logs" -> logs(c)
        "terminal" -> terminal(c)
        "shield" -> shield(c)
        "settings" -> settings(c)
        "nvr" -> nvr(c)
        "calendar" -> calendar(c)
        "contacts" -> contacts(c)
        "mail" -> mail(c)
        "users" -> users(c)
        "planner" -> planner(c)
        "account" -> account(c)
        "overview" -> overview(c)
        "screen" -> screen(c)
        "photos" -> photos(c)
        "bell" -> bell(c)
        else -> null
    }

    private fun files(c: Color): ImageVector = ImageVector.Builder("files", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M2.5 6.5A2.5 2.5 0 0 1 5 4h4.2l2.3 2.3H19a2.5 2.5 0 0 1 2.5 2.5V9H2.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.55f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M2.5 9.5h19v8A2.5 2.5 0 0 1 19 20H5a2.5 2.5 0 0 1-2.5-2.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun docker(c: Color): ImageVector = ImageVector.Builder("docker", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M4.8 8H7.2A0.8 0.8 0 0 1 8 8.8V10.6A0.8 0.8 0 0 1 7.2 11.4H4.8A0.8 0.8 0 0 1 4 10.6V8.8A0.8 0.8 0 0 1 4.8 8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M9.8 8H12.2A0.8 0.8 0 0 1 13 8.8V10.6A0.8 0.8 0 0 1 12.2 11.4H9.8A0.8 0.8 0 0 1 9 10.6V8.8A0.8 0.8 0 0 1 9.8 8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M14.8 8H17.2A0.8 0.8 0 0 1 18 8.8V10.6A0.8 0.8 0 0 1 17.2 11.4H14.8A0.8 0.8 0 0 1 14 10.6V8.8A0.8 0.8 0 0 1 14.8 8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M9.8 4H12.2A0.8 0.8 0 0 1 13 4.8V6.6A0.8 0.8 0 0 1 12.2 7.4H9.8A0.8 0.8 0 0 1 9 6.6V4.8A0.8 0.8 0 0 1 9.8 4Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M2.5 12.6h19c-0.3 4.4-4.2 7.4-9.5 7.4s-9-3-9.5-7.4Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M6 15.6A1 1 0 1 0 8 15.6A1 1 0 1 0 6 15.6Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun vm(c: Color): ImageVector = ImageVector.Builder("vm", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5 3.5H19A2.5 2.5 0 0 1 21.5 6V14A2.5 2.5 0 0 1 19 16.5H5A2.5 2.5 0 0 1 2.5 14V6A2.5 2.5 0 0 1 5 3.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M6 6H18A1 1 0 0 1 19 7V13A1 1 0 0 1 18 14H6A1 1 0 0 1 5 13V7A1 1 0 0 1 6 6Z"),
            fill = SolidColor(c),
            fillAlpha = 0.35f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M9 20.5h6M12 16.5v4"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun lxc(c: Color): ImageVector = ImageVector.Builder("lxc", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M12 2.5 3 7.5l9 5 9-5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M3 8.5v8l8.4 4.7v-8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M21 8.5v8l-8.4 4.7v-8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.45f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun storage(c: Color): ImageVector = ImageVector.Builder("storage", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.1 3H18.9A1.6 1.6 0 0 1 20.5 4.6V6.6A1.6 1.6 0 0 1 18.9 8.2H5.1A1.6 1.6 0 0 1 3.5 6.6V4.6A1.6 1.6 0 0 1 5.1 3Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5.1 9.4H18.9A1.6 1.6 0 0 1 20.5 11V13A1.6 1.6 0 0 1 18.9 14.6H5.1A1.6 1.6 0 0 1 3.5 13V11A1.6 1.6 0 0 1 5.1 9.4Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.8f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5.1 15.8H18.9A1.6 1.6 0 0 1 20.5 17.4V19.4A1.6 1.6 0 0 1 18.9 21H5.1A1.6 1.6 0 0 1 3.5 19.4V17.4A1.6 1.6 0 0 1 5.1 15.8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.6f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M16 5.6A1 1 0 1 0 18 5.6A1 1 0 1 0 16 5.6Z"),
            fill = SolidColor(Color(0xFF4ADE80)),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M16 12A1 1 0 1 0 18 12A1 1 0 1 0 16 12Z"),
            fill = SolidColor(Color(0xFF4ADE80)),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M16 18.4A1 1 0 1 0 18 18.4A1 1 0 1 0 16 18.4Z"),
            fill = SolidColor(Color(0xFF4ADE80)),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun shares(c: Color): ImageVector = ImageVector.Builder("shares", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M2.8 12A3.2 3.2 0 1 0 9.2 12A3.2 3.2 0 1 0 2.8 12Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M14.8 5.5A3.2 3.2 0 1 0 21.2 5.5A3.2 3.2 0 1 0 14.8 5.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M14.8 18.5A3.2 3.2 0 1 0 21.2 18.5A3.2 3.2 0 1 0 14.8 18.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M8.5 10.6 15.4 7M8.5 13.4l6.9 3.6"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun backup(c: Color): ImageVector = ImageVector.Builder("backup", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M12 3a9 9 0 1 1-8.5 6"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M2.5 3.8v5.6h5.6Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M12 7.5V12l3.2 2"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun store(c: Color): ImageVector = ImageVector.Builder("store", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.4 3H8.6A2.4 2.4 0 0 1 11 5.4V8.6A2.4 2.4 0 0 1 8.6 11H5.4A2.4 2.4 0 0 1 3 8.6V5.4A2.4 2.4 0 0 1 5.4 3Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M15.4 3H18.6A2.4 2.4 0 0 1 21 5.4V8.6A2.4 2.4 0 0 1 18.6 11H15.4A2.4 2.4 0 0 1 13 8.6V5.4A2.4 2.4 0 0 1 15.4 3Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5.4 13H8.6A2.4 2.4 0 0 1 11 15.4V18.6A2.4 2.4 0 0 1 8.6 21H5.4A2.4 2.4 0 0 1 3 18.6V15.4A2.4 2.4 0 0 1 5.4 13Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M17 13.5v7M13.5 17h7"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun agent(c: Color): ImageVector = ImageVector.Builder("agent", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M8.3 12A3.7 3.7 0 1 0 15.7 12A3.7 3.7 0 1 0 8.3 12Z"),
            fill = SolidColor(Color(0xFFF2915F)),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M9.5 10.7A1.3 1.3 0 1 0 12.1 10.7A1.3 1.3 0 1 0 9.5 10.7Z"),
            fill = SolidColor(Color(0xFFFFD9BF)),
            fillAlpha = 0.8f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M12 5.2a6.8 6.8 0 0 1 6.4 4.5M12 18.8a6.8 6.8 0 0 1-6.4-4.5"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color(0xFFE07A4C)),
            strokeAlpha = 1f,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M20.3 12a8.3 8.3 0 0 1-2.4 5.9M3.7 12a8.3 8.3 0 0 1 2.4-5.9"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color(0xFFE07A4C)),
            strokeAlpha = 0.6f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun monitor(c: Color): ImageVector = ImageVector.Builder("monitor", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.5 3.5H18.5A3 3 0 0 1 21.5 6.5V17.5A3 3 0 0 1 18.5 20.5H5.5A3 3 0 0 1 2.5 17.5V6.5A3 3 0 0 1 5.5 3.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.25f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5 16l3.5-5 3 2.5L16 7l3 5"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    private fun logs(c: Color): ImageVector = ImageVector.Builder("logs", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M6 2.5h8.5L19 7v13a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 20V4A1.5 1.5 0 0 1 6 2.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M8.5 11h7M8.5 14.5h7M8.5 18h4"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(c),
            strokeAlpha = 1f,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun terminal(c: Color): ImageVector = ImageVector.Builder("terminal", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.5 3.5H18.5A3 3 0 0 1 21.5 6.5V17.5A3 3 0 0 1 18.5 20.5H5.5A3 3 0 0 1 2.5 17.5V6.5A3 3 0 0 1 5.5 3.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.2f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("m6.5 9 3.5 3-3.5 3"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M12.5 16h5"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun shield(c: Color): ImageVector = ImageVector.Builder("shield", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M12 2.5 4 5.5v6c0 5.2 3.4 8.8 8 10 4.6-1.2 8-4.8 8-10v-6Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("m8.5 12 2.5 2.5 4.5-5"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(c),
            strokeAlpha = 1f,
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    private fun settings(c: Color): ImageVector = ImageVector.Builder("settings", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M4 6.5h16M4 12h16M4 17.5h16"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(c),
            strokeAlpha = 0.45f,
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M12.4 6.5A2.6 2.6 0 1 0 17.6 6.5A2.6 2.6 0 1 0 12.4 6.5Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5.9 12A2.6 2.6 0 1 0 11.1 12A2.6 2.6 0 1 0 5.9 12Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M10.4 17.5A2.6 2.6 0 1 0 15.6 17.5A2.6 2.6 0 1 0 10.4 17.5Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun nvr(c: Color): ImageVector = ImageVector.Builder("nvr", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.2 5H14.3A2.2 2.2 0 0 1 16.5 7.2V12.3A2.2 2.2 0 0 1 14.3 14.5H5.2A2.2 2.2 0 0 1 3 12.3V7.2A2.2 2.2 0 0 1 5.2 5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("m16.5 8.2 4.3-2.4v8.4l-4.3-2.4Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5.5 8.6A1.5 1.5 0 1 0 8.5 8.6A1.5 1.5 0 1 0 5.5 8.6Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M9.7 14.5v3.2M6 19.5h7.4"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun calendar(c: Color): ImageVector = ImageVector.Builder("calendar", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M6 4.5H18A3 3 0 0 1 21 7.5V18A3 3 0 0 1 18 21H6A3 3 0 0 1 3 18V7.5A3 3 0 0 1 6 4.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M3 9.5h18V7.5a3 3 0 0 0-3-3H6a3 3 0 0 0-3 3Z"),
            fill = SolidColor(c),
            fillAlpha = 0.55f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M8 2.8v3.6M16 2.8v3.6"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M7.1 12H8.9A0.6 0.6 0 0 1 9.5 12.6V14A0.6 0.6 0 0 1 8.9 14.6H7.1A0.6 0.6 0 0 1 6.5 14V12.6A0.6 0.6 0 0 1 7.1 12Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M11.1 12H12.9A0.6 0.6 0 0 1 13.5 12.6V14A0.6 0.6 0 0 1 12.9 14.6H11.1A0.6 0.6 0 0 1 10.5 14V12.6A0.6 0.6 0 0 1 11.1 12Z"),
            fill = SolidColor(c),
            fillAlpha = 0.45f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M15.1 12H16.9A0.6 0.6 0 0 1 17.5 12.6V14A0.6 0.6 0 0 1 16.9 14.6H15.1A0.6 0.6 0 0 1 14.5 14V12.6A0.6 0.6 0 0 1 15.1 12Z"),
            fill = SolidColor(c),
            fillAlpha = 0.45f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M7.1 16H8.9A0.6 0.6 0 0 1 9.5 16.6V18A0.6 0.6 0 0 1 8.9 18.6H7.1A0.6 0.6 0 0 1 6.5 18V16.6A0.6 0.6 0 0 1 7.1 16Z"),
            fill = SolidColor(c),
            fillAlpha = 0.45f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M11.1 16H12.9A0.6 0.6 0 0 1 13.5 16.6V18A0.6 0.6 0 0 1 12.9 18.6H11.1A0.6 0.6 0 0 1 10.5 18V16.6A0.6 0.6 0 0 1 11.1 16Z"),
            fill = SolidColor(c),
            fillAlpha = 0.45f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun contacts(c: Color): ImageVector = ImageVector.Builder("contacts", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M7 2.5H17A3 3 0 0 1 20 5.5V18.5A3 3 0 0 1 17 21.5H7A3 3 0 0 1 4 18.5V5.5A3 3 0 0 1 7 2.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M8.8 10A3.2 3.2 0 1 0 15.2 10A3.2 3.2 0 1 0 8.8 10Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M6.8 18.2a5.2 5.2 0 0 1 10.4 0Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M2.5 7h2.2M2.5 12h2.2M2.5 17h2.2"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 0.7f,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun mail(c: Color): ImageVector = ImageVector.Builder("mail", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5 5H19A2.5 2.5 0 0 1 21.5 7.5V16.5A2.5 2.5 0 0 1 19 19H5A2.5 2.5 0 0 1 2.5 16.5V7.5A2.5 2.5 0 0 1 5 5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("m4 7.2 8 6 8-6"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(c),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    private fun users(c: Color): ImageVector = ImageVector.Builder("users", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.4 8A3.6 3.6 0 1 0 12.6 8A3.6 3.6 0 1 0 5.4 8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M2.5 20a6.5 6.5 0 0 1 13 0Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M14.4 9A2.6 2.6 0 1 0 19.6 9A2.6 2.6 0 1 0 14.4 9Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M15.8 14.2A5 5 0 0 1 21.5 20h-4.2a8 8 0 0 0-1.5-5.8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun planner(c: Color): ImageVector = ImageVector.Builder("planner", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.5 4.5H18.5A2.5 2.5 0 0 1 21 7V18A2.5 2.5 0 0 1 18.5 20.5H5.5A2.5 2.5 0 0 1 3 18V7A2.5 2.5 0 0 1 5.5 4.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5 4.5H19A2 2 0 0 1 21 6.5V7A2 2 0 0 1 19 9H5A2 2 0 0 1 3 7V6.5A2 2 0 0 1 5 4.5Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.6f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M8 3v3M16 3v3"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("m8.5 14.5 2.3 2.3 4.7-4.8"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(c),
            strokeAlpha = 1f,
            strokeLineWidth = 2.1f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    private fun account(c: Color): ImageVector = ImageVector.Builder("account", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M7.8 8.5A4.2 4.2 0 1 0 16.2 8.5A4.2 4.2 0 1 0 7.8 8.5Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M4 20.5a8 8 0 0 1 16 0Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun overview(c: Color): ImageVector = ImageVector.Builder("overview", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5 3H9A2 2 0 0 1 11 5V11A2 2 0 0 1 9 13H5A2 2 0 0 1 3 11V5A2 2 0 0 1 5 3Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M15 3H19A2 2 0 0 1 21 5V7A2 2 0 0 1 19 9H15A2 2 0 0 1 13 7V5A2 2 0 0 1 15 3Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M15 11H19A2 2 0 0 1 21 13V19A2 2 0 0 1 19 21H15A2 2 0 0 1 13 19V13A2 2 0 0 1 15 11Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M5 15H9A2 2 0 0 1 11 17V19A2 2 0 0 1 9 21H5A2 2 0 0 1 3 19V17A2 2 0 0 1 5 15Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun screen(c: Color): ImageVector = ImageVector.Builder("screen", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5 4H19A2.5 2.5 0 0 1 21.5 6.5V14.5A2.5 2.5 0 0 1 19 17H5A2.5 2.5 0 0 1 2.5 14.5V6.5A2.5 2.5 0 0 1 5 4Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("m10 8 5 2.5-5 2.5Z"),
            fill = SolidColor(c),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M8 20.5h8"),
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.White),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun photos(c: Color): ImageVector = ImageVector.Builder("photos", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M5.5 4H18.5A3 3 0 0 1 21.5 7V17A3 3 0 0 1 18.5 20H5.5A3 3 0 0 1 2.5 17V7A3 3 0 0 1 5.5 4Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M2.5 16.5 8 11l4.5 4.5 3-3 6 6V17a3 3 0 0 1-3 3H5.5a3 3 0 0 1-3-3Z"),
            fill = SolidColor(c),
            fillAlpha = 0.55f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M14 8.8A2 2 0 1 0 18 8.8A2 2 0 1 0 14 8.8Z"),
            fill = SolidColor(c),
            fillAlpha = 0.7f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    private fun bell(c: Color): ImageVector = ImageVector.Builder("bell", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M12 2.8a6.2 6.2 0 0 0-6.2 6.2v4.6L3.8 17h16.4l-2-3.4V9A6.2 6.2 0 0 0 12 2.8Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        addPath(
            pathData = addPathNodes("M9.2 18.6a2.8 2.8 0 0 0 5.6 0Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.75f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()
}

/** Verläufe der Programmsymbole (GRADIENTS): hell oben links, dunkel unten rechts. */
object ProgramGradients {
    val all: Map<String, Pair<Color, Color>> = mapOf(
        "blue" to (Color(0xFF5AA9FF) to Color(0xFF1F6FE0)),
        "teal" to (Color(0xFF36D1C4) to Color(0xFF0E8F9A)),
        "green" to (Color(0xFF4FD67A) to Color(0xFF1B9A4B)),
        "orange" to (Color(0xFFFFB547) to Color(0xFFF07A1A)),
        "violet" to (Color(0xFFA78BFA) to Color(0xFF6D3FE0)),
        "grey" to (Color(0xFFE8ECF1) to Color(0xFFA4B0BD)),
        "dark" to (Color(0xFF4A5563) to Color(0xFF1F2730)),
        "red" to (Color(0xFFFF7A7A) to Color(0xFFE0393E)),
        "yellow" to (Color(0xFFFFD84D) to Color(0xFFF0A91A)),
        "pink" to (Color(0xFFFF8FC7) to Color(0xFFE0479A)),
        "ember" to (Color(0xFF2C1F18) to Color(0xFF0D0F14)),
    )

    fun of(name: String): Pair<Color, Color> = all[name] ?: all.getValue("blue")
}
