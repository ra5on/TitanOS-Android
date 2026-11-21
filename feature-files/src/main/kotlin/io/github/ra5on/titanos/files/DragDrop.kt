package io.github.ra5on.titanos.files

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import io.github.ra5on.titanos.model.FileEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ziehen und Ablegen (Inhaber 21.11.2026): zwischen Ordnern, zwischen zwei
 * Fenstern der App und aus/in andere Apps. Gezogen wird eine ClipData mit
 * content://-Adressen des DocumentsProviders (andere Apps lesen darüber) und
 * einer Beschreibung der Pfade am NAS (die App selbst kopiert oder
 * verschiebt dann am NAS, ohne die Datei herunterzuladen).
 */
object DragDrop {
    const val LABEL = "titanos-files"
    const val MIME = "application/x-titanos-files"

    @Serializable
    data class Payload(val root: String, val paths: List<String>)

    fun transfer(context: Context, location: Location, entries: List<FileEntry>): DragAndDropTransferData? {
        if (entries.isEmpty()) return null
        val payload = Json.encodeToString(Payload.serializer(), Payload(location.root.id, entries.map { location.full(it.name) }))
        val uris = entries.map { NasDocumentsProvider.uriOf(context, location.root.id, location.full(it.name)) }
        val mimes = (entries.map { if (it.dir) android.provider.DocumentsContract.Document.MIME_TYPE_DIR else NasDocumentsProvider.mimeOf(it.name) } + MIME).distinct()
        val clip = ClipData(ClipDescription(LABEL, mimes.toTypedArray()), ClipData.Item(payload, null, null, uris[0]))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return DragAndDropTransferData(clip, payload, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
    }

    /** Ob die App mit dem Abgelegten etwas anfangen kann. */
    fun accepts(event: DragAndDropEvent): Boolean {
        val d = event.toAndroidDragEvent().clipDescription ?: return false
        return d.label == LABEL || d.hasMimeType(MIME) || d.mimeTypeCount > 0
    }

    /** Pfade am NAS, wenn es aus TitanOS selbst kommt (auch aus einem anderen Fenster). */
    fun payload(event: DragAndDropEvent): Payload? {
        val e = event.toAndroidDragEvent()
        val clip = e.clipData ?: return null
        if (clip.description?.label != LABEL) return null
        val text = clip.getItemAt(0)?.text?.toString() ?: return null
        return runCatching { Json.decodeFromString(Payload.serializer(), text) }.getOrNull()
    }

    /** Dateien aus einer anderen App: Leserecht für die Dauer des Ablegens holen. */
    fun uris(context: Context, event: DragAndDropEvent): List<Uri> {
        val e = event.toAndroidDragEvent()
        val clip = e.clipData ?: return emptyList()
        context.findActivity()?.requestDragAndDropPermissions(e)
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
