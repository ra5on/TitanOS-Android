package io.github.ra5on.titanos.mail

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent

/*
 * Ziehen und Ablegen auf großen Bildschirmen (MessageList/FolderColumn im Web):
 * Mails auf einen Ordner desselben Postfachs, Anhänge als content://-Datei
 * in andere Apps, Dateien anderer Apps ins Verfassen.
 */

internal const val MAIL_DRAG_LABEL = "titanos-mail"

/** Gezogene Mails: Postfach, Ordner und uids (nur innerhalb der App). */
data class MailDrag(val account: String, val folder: String, val uids: List<Long>) {
    fun encode(): String = "$account\u0000$folder\u0000${uids.joinToString(",")}"

    companion object {
        fun decode(s: String): MailDrag? {
            val p = s.split('\u0000')
            if (p.size != 3 || p[0].isEmpty()) return null
            val uids = p[2].split(',').mapNotNull { it.toLongOrNull() }
            return if (uids.isEmpty()) null else MailDrag(p[0], p[1], uids)
        }
    }
}

/**
 * Ziehen beginnt nach langem Drücken (Finger) bzw. sofort mit gedrückter
 * Maustaste; ein kurzes Tippen bleibt ein Klick (der eingebaute Erkenner
 * von Compose würde den Klick verschlucken).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.dragOnLongPress(enabled: Boolean, data: () -> DragAndDropTransferData?): Modifier =
    if (!enabled) this else dragAndDropSource(
        drawDragDecoration = {
            drawRoundRect(androidx.compose.ui.graphics.Color(0x552563EB), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f))
        },
        block = {
            detectDragGesturesAfterLongPress(onDragStart = { data()?.let { startTransfer(it) } }, onDrag = { _, _ -> })
        },
    )

/** Zeilen ziehen (nur auf großen Bildschirmen). */
fun Modifier.mailDragSource(enabled: Boolean, drag: () -> MailDrag?): Modifier = dragOnLongPress(enabled) {
    val d = drag() ?: return@dragOnLongPress null
    DragAndDropTransferData(ClipData(ClipDescription(MAIL_DRAG_LABEL, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item(d.encode())), flags = 0)
}

private fun mailDragOf(event: DragAndDropEvent): MailDrag? {
    val e = event.toAndroidDragEvent()
    if (e.clipDescription?.label?.toString() != MAIL_DRAG_LABEL) return null
    val clip = e.clipData ?: return null
    if (clip.itemCount == 0) return null
    return MailDrag.decode(clip.getItemAt(0).text?.toString().orEmpty())
}

/**
 * Ein Ordner nimmt gezogene Mails an – nur aus demselben Postfach (Verschieben
 * geht nie über Postfächer, wie im Web). [onHover] zeigt das Ziel an.
 */
@Composable
fun Modifier.mailDropTarget(enabled: Boolean, account: String, folder: String, onHover: (Boolean) -> Unit, onDrop: (MailDrag) -> Unit): Modifier {
    if (!enabled) return this
    val drop by rememberUpdatedState(onDrop)
    val hover by rememberUpdatedState(onHover)
    val target = remember(account, folder) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val d = mailDragOf(event) ?: return false
                if (d.account != account || d.folder == folder) return false
                drop(d)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { e -> e.toAndroidDragEvent().clipDescription?.label?.toString() == MAIL_DRAG_LABEL },
        target = target,
    )
}

/** Ein Anhang als Datei in andere Apps ziehen; [uri] ist null, solange er noch nicht geladen ist. */
fun Modifier.attachmentDragSource(enabled: Boolean, type: String, uri: () -> Uri?): Modifier = dragOnLongPress(enabled) {
    val u = uri() ?: return@dragOnLongPress null
    DragAndDropTransferData(
        ClipData(ClipDescription("attachment", arrayOf(type.ifBlank { "application/octet-stream" })), ClipData.Item(u)),
        flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ,
    )
}

/** Dateien aus anderen Apps ablegen (Verfassen): content://-Adressen mit der Erlaubnis des Ziehens. */
@Composable
fun Modifier.fileDropTarget(enabled: Boolean, activity: Activity?, onHover: (Boolean) -> Unit, onUris: (List<Uri>) -> Unit): Modifier {
    if (!enabled || activity == null) return this
    val uris by rememberUpdatedState(onUris)
    val hover by rememberUpdatedState(onHover)
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val e = event.toAndroidDragEvent()
                activity.requestDragAndDropPermissions(e)
                val clip = e.clipData ?: return false
                val list = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (list.isEmpty()) return false
                uris(list)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { e -> e.mimeTypes().any { it != ClipDescription.MIMETYPE_TEXT_PLAIN || e.toAndroidDragEvent().clipDescription?.label?.toString() != MAIL_DRAG_LABEL } && e.toAndroidDragEvent().clipDescription?.label?.toString() != MAIL_DRAG_LABEL },
        target = target,
    )
}
