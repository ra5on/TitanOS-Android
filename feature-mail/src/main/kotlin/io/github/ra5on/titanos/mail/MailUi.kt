package io.github.ra5on.titanos.mail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.SwipeDefaults
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.CertificateChangedException
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.generated.IconSquareColors
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlin.math.abs
import kotlin.math.roundToInt

/** Strichsymbole, die nur Mail braucht (24er Raster wie TitanIcons). */
object MailIcons {
    private fun line(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()

    val reply by lazy { line("reply", "M10 8 5 12l5 4M5 12h9a5 5 0 0 1 5 5v1") }
    val replyAll by lazy { line("replyAll", "M11 8 6 12l5 4M6 12h8a5 5 0 0 1 5 5v1M7 8 2 12l5 4") }
    val forward by lazy { line("forward", "m14 8 5 4-5 4M19 12h-9a5 5 0 0 0-5 5v1") }
    val flag by lazy { line("flag", "M6 21V4M6 4h11l-2 4 2 4H6") }
    val clip by lazy { line("clip", "m20 11-8.5 8.5a5 5 0 0 1-7-7L13 4a3.5 3.5 0 0 1 5 5l-8.6 8.6a2 2 0 0 1-2.8-2.8L14 8.5") }
    val filter by lazy { line("filter", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM7.5 9h9M9.5 12.5h5M11 16h2") }
    val star by lazy { line("star", "m12 3.5 2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9Z") }
    val junk by lazy { line("junk", "M12 3 5 6v5c0 4.6 3 8 7 9 4-1 7-4.4 7-9V6ZM9.5 12l2 2 3.5-3.5") }
    val unread by lazy { line("unread", "M3 6h18v12H3ZM3.5 7l8.5 6 8.5-6M19 3.5h.01") }
    val grip by lazy { line("grip", "M5 9h14M5 15h14") }
    val up by lazy { line("up", "m6 15 6-6 6 6") }
    val link by lazy { line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val open by lazy { line("open", "M14 4h6v6M20 4l-9 9M18 14v5H5V6h5") }
    val person by lazy { line("person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM5 20a7 7 0 0 1 14 0") }
}

/** Symbol eines Ordners nach seiner Rolle. */
fun folderIcon(role: String): ImageVector = when (role) {
    "inbox" -> TitanIcons.mail
    "drafts" -> TitanIcons.edit
    "sent" -> TitanIcons.send
    "trash" -> TitanIcons.trash
    "junk" -> MailIcons.junk
    "archive" -> TitanIcons.archive
    else -> TitanIcons.folder
}

/** Der Name eines Ordners: seine Rolle in der Sprache der Oberfläche, sonst sein eigener. */
@Composable
fun folderName(f: MailFolder): String = when (f.role) {
    "inbox" -> stringResource(R.string.mail_role_inbox)
    "sent" -> stringResource(R.string.mail_role_sent)
    "drafts" -> stringResource(R.string.mail_role_drafts)
    "trash" -> stringResource(R.string.mail_role_trash)
    "junk" -> stringResource(R.string.mail_role_junk)
    "archive" -> stringResource(R.string.mail_role_archive)
    else -> f.name.ifBlank { f.path.split('/', '.').last().ifBlank { f.path } }
}

/** Ein Fehler der Mail-API in Worten (error.mail_* der Weboberfläche). */
@Composable
fun errorText(e: Throwable?): String {
    val code = (e as? ApiException)?.code.orEmpty()
    val id = when (code) {
        "mail_disabled" -> R.string.error_mail_disabled
        "mail_auth_failed" -> R.string.error_mail_auth_failed
        "mail_unreachable" -> R.string.error_mail_unreachable
        "mail_unavailable" -> R.string.error_mail_unavailable
        "mail_tls" -> R.string.error_mail_tls
        "mail_host_forbidden" -> R.string.error_mail_host_forbidden
        "mail_timeout" -> R.string.error_mail_timeout
        "mail_too_large" -> R.string.error_mail_too_large
        "mail_not_found" -> R.string.error_mail_not_found
        "mail_folder_exists" -> R.string.error_mail_folder_exists
        "mail_send_failed" -> R.string.error_mail_send_failed
        "mail_password_required" -> R.string.error_mail_password_required
        "mail_no_password" -> R.string.error_mail_no_password
        "mail_invalid" -> R.string.error_mail_invalid
        "mail_settings_unreadable" -> R.string.error_mail_settings_unreadable
        "mail_rate_limited" -> R.string.error_mail_rate_limited
        "mail_busy" -> R.string.error_mail_busy
        "mail_key_missing" -> R.string.error_mail_key_missing
        "mail_key_damaged" -> R.string.error_mail_key_damaged
        "file_exists" -> R.string.mailx_file_exists
        else -> null
    }
    if (id != null) return stringResource(id)
    if (e is CertificateChangedException) return stringResource(R.string.mailx_cert_changed)
    if (e is ApiException && e.message?.isNotBlank() == true) return stringResource(R.string.mailx_error_detail, e.message.orEmpty())
    return stringResource(R.string.mailx_unreachable)
}

/** Kennt die Prüfung einen Code, sagt sie ihn in Worten (resultText). */
@Composable
fun codeText(code: String): String = errorText(if (code.isEmpty()) null else ApiException(0, code, ""))

private val AVATAR_COLORS = listOf(
    IconSquareColors.blue, IconSquareColors.violet, IconSquareColors.teal, IconSquareColors.cyan, IconSquareColors.green,
    IconSquareColors.orange, IconSquareColors.amber, IconSquareColors.red, IconSquareColors.pink, IconSquareColors.slate,
)

/** Die Farbe einer Adresse (FNV-1a wie avatar.ts), immer dieselbe. */
fun avatarColor(addr: String): Color {
    var h = 0x811c9dc5.toInt()
    for (ch in addr.trim().lowercase()) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return AVATAR_COLORS[(h.toLong() and 0xffffffffL).rem(AVATAR_COLORS.size).toInt()]
}

/** Bild des Absenders aus den Kontakten, sonst Initialen in seiner Farbe. */
@Composable
fun SenderAvatar(model: MailModel?, from: Address, size: Dp = 40.dp) {
    val photo = model?.photo(from.addr)
    // Initialen nur als Schmuck: der Name steht daneben, TalkBack liest ihn nicht doppelt.
    Box(Modifier.size(size).clip(CircleShape).background(avatarColor(from.addr)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (photo != null) {
            Image(photo, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            val name = from.name.ifBlank { from.addr.substringBefore('@') }
            // Initialen in fester Größe zum Kreis: große Schrift des Systems sprengt ihn nicht.
            val fixed = with(LocalDensity.current) { (size * 0.38f).toSp() }
            Text(initials(name), style = TitanType.rowTitle.copy(fontSize = fixed, lineHeight = fixed), color = IconSquareColors.on)
        }
    }
}

/** Ein Textknopf in der Akzentfarbe (Bearbeiten, Fertig, Alle auswählen). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, enabled: Boolean = true, bold: Boolean = false, danger: Boolean = false) {
    val c = Titan.colors
    Text(
        text,
        style = TitanType.button.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = when {
            !enabled -> c.muted
            danger -> c.dangerInk
            else -> c.accent
        },
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 11.dp),
    )
}

/** Leiste unten (Entwurf 41): links ein Knopf, Mitte Stand, rechts „Neue E-Mail“. */
@Composable
fun MailBottomBar(
    title: String?,
    sub: String?,
    left: (@Composable () -> Unit)? = null,
    onCompose: (() -> Unit)?,
) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth().background(c.header)) {
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.widthIn(min = 48.dp)) { left?.invoke() }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (title != null) Text(title, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text, textAlign = TextAlign.Center)
                if (sub != null) Text(sub, style = TitanType.small, color = c.muted, textAlign = TextAlign.Center)
            }
            Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                if (onCompose != null) {
                    io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.edit, stringResource(R.string.mail_action_compose), onCompose, tint = c.accent)
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** Ein Knopf mit Symbol und Beschriftung für Leisten unten. */
@Composable
fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val c = Titan.colors
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (!enabled) c.muted.copy(alpha = .5f) else if (danger) c.dangerInk else c.accent, modifier = Modifier.size(24.dp))
    }
}

/** Eine Wahl aus mehreren (Zeilen mit Haken), z. B. im Filter-Blatt. */
@Composable
fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit, divider: Boolean = false, sub: String? = null) {
    io.github.ra5on.titanos.ui.components.ListRow(
        text,
        sub = sub,
        divider = divider,
        right = { RadioMark(selected) },
        onClick = onClick,
    )
}

/** Eine Zeile mit Schalter; der Zustand steht in Worten für Bildschirmleser. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, divider: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    io.github.ra5on.titanos.ui.components.ListRow(
        title,
        sub = sub,
        divider = divider,
        icon = icon?.let { { io.github.ra5on.titanos.ui.components.RowIcon(it) } },
        right = { Toggle(checked, onChange, stringResource(UiR.string.ui_on), stringResource(UiR.string.ui_off), enabled = enabled) },
        onClick = if (enabled) ({ onChange(!checked) }) else null,
    )
}

/** Beschriftetes Eingabefeld mit Hinweis darunter. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    password: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        if (password) {
            PasswordInput(value, onChange, label)
        } else {
            io.github.ra5on.titanos.ui.components.TextInput(value, onChange, label, keyboard = keyboard, placeholder = placeholder)
        }
        if (hint != null) Text(hint, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
    }
}

/** Ein Passwortfeld: Punkte statt Zeichen, kein Vorschlag der Tastatur, nie gespeichert. */
@Composable
fun PasswordInput(value: String, onChange: (String) -> Unit, label: String) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize, color = c.text),
        cursorBrush = SolidColor(c.accent),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(c.surface)
                    .then(Modifier.padding(0.dp)),
            ) {
                Box(Modifier.fillMaxWidth().clip(shape).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp)) { inner() }
            }
        },
    )
}

/** Ein Abschnitt mit Überschrift und Karte (Einstellungen). */
@Composable
fun Group(title: String?, desc: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SectionHeader(title)
    if (desc != null) Text(desc, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
    io.github.ra5on.titanos.ui.components.Card(content = content)
}

/** Ein Hinweis in Grün: geschafft (immer mit Haken, nie nur Farbe). */
@Composable
fun Done(text: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.okBg).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TitanIcons.check, null, tint = c.okText, modifier = Modifier.size(20.dp))
        Text(text, style = TitanType.body, color = c.okText)
    }
}

/** Eine Aktion beim Wischen einer Zeile. */
data class Swipe(val label: String, val icon: ImageVector, val color: Color, val run: () -> Unit)

/**
 * Wischen wie in Apple Mail (SwipeRow der Weboberfläche): nach rechts die
 * führende Aktion, nach links die Knöpfe; ganz durchgewischt die erste.
 * Für Bildschirmleser stehen alle als eigene Aktionen da.
 */
@Composable
fun SwipeRow(leading: Swipe?, trailing: List<Swipe>, enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    val still = reduceMotion()
    // Zurückschnappen weich, außer beim Ziehen und wenn Android Animationen abgeschaltet hat.
    val shown by androidx.compose.animation.core.animateFloatAsState(
        offset,
        if (still || dragging) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180),
        label = "swipe",
    )
    val density = LocalDensity.current
    val button = with(density) { 76.dp.toPx() }
    val open = button * trailing.size
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                customActions = (listOfNotNull(leading) + trailing).map { s -> CustomAccessibilityAction(s.label) { s.run(); true } }
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(trailing.size, leading) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            // Dieselbe Schwelle wie in allen Listen (core-ui SwipeDefaults): weit durchziehen
                            // führt aus; links stehen hier mehrere Knöpfe, also erst hinter ihnen.
                            val extra = with(density) { SwipeDefaults.RUN_EXTRA_DP.dp.toPx() }
                            val reveal = with(density) { SwipeDefaults.REVEAL_DP.dp.toPx() }
                            when {
                                leading != null && offset >= SwipeDefaults.runAt(width, reveal, extra) -> { leading.run(); offset = 0f }
                                trailing.isNotEmpty() && -offset >= SwipeDefaults.runAt(width, open, extra) -> { trailing.first().run(); offset = 0f }
                                offset < -open / 2 -> offset = -open
                                else -> offset = 0f
                            }
                        },
                        onDragCancel = { dragging = false; offset = 0f },
                    ) { _, drag ->
                        offset = (offset + drag).coerceIn(if (trailing.isEmpty()) 0f else -width, if (leading == null) 0f else width * 0.9f)
                    }
                },
            ),
    ) {
        // Hinter der Zeile: links die führende Aktion, rechts die Knöpfe.
        if (shown > 0 && leading != null) {
            Row(Modifier.matchParentSize().background(leading.color).padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SwipeLabel(leading)
            }
        }
        if (shown < 0 && trailing.isNotEmpty()) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                for (s in trailing.reversed()) {
                    Box(
                        Modifier.width(76.dp).fillMaxHeight().background(s.color).clickable(role = Role.Button) { offset = 0f; s.run() },
                        contentAlignment = Alignment.Center,
                    ) { SwipeLabel(s) }
                }
            }
        }
        Box(Modifier.offset { IntOffset(shown.roundToInt(), 0) }.background(Titan.colors.bg).fillMaxWidth()) {
            content()
            if (abs(offset) > 1f) Box(Modifier.matchParentSize().clickable { offset = 0f })
        }
    }
}

@Composable
private fun SwipeLabel(s: Swipe) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Icon(s.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(s.label, style = TitanType.small, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** „Animationen entfernen“ in den Bedienungshilfen bzw. Animationsskala 0: dann nichts bewegen. */
@Composable
fun reduceMotion(): Boolean = io.github.ra5on.titanos.ui.components.reduceMotion()

/** Eine echte Tastatur ist angeschlossen (Desktop-Modus, Tastatur-Hülle): dann gelten die Tastenkürzel. */
@Composable
fun hasKeyboard(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY

/** Die Sprache der Oberfläche, beobachtbar für Compose. */
@Composable
fun currentLocale(): java.util.Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

/** Abstand. */
@Composable
fun Gap(h: Dp) = Spacer(Modifier.heightIn(min = h, max = h))
