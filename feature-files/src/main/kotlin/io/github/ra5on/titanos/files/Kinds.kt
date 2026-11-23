package io.github.ra5on.titanos.files

/** Wie die App eine Datei zeigt; nach der Endung wie das Web (file-actions.ts, thumbs/render.go). */
enum class FileKind { Image, Video, Audio, Pdf, Text, Office, Archive, Other }

object Kinds {
    /** Bilder, die Android selbst lesen kann (auch HEIC/AVIF ohne Vorschau vom NAS). */
    val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "dng")
    val VIDEOS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "ogv", "mts", "m2ts", "wmv", "3gp", "ts")
    val AUDIO = setOf("mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus", "wma", "amr")

    /** Was `GET /files/text` und der Editor kennen (files/text.go). */
    val TEXT = setOf(
        "txt", "md", "markdown", "json", "yaml", "yml", "ini", "conf", "cfg", "toml", "xml", "csv", "tsv", "log",
        "sh", "bash", "py", "js", "ts", "html", "css", "sql", "env",
    )
    val TEXT_NAMES = setOf("Dockerfile", "Makefile", ".env")

    /** Weitere Quelltexte: nur lesend aus den Bytes (das NAS bearbeitet sie nicht). */
    val CODE = setOf(
        "kt", "kts", "java", "go", "c", "h", "cpp", "hpp", "cc", "rs", "swift", "rb", "php", "pl", "lua", "r", "scala",
        "gradle", "properties", "svelte", "vue", "jsx", "tsx", "mjs", "cjs", "scss", "less", "bat", "ps1", "cmake",
        "gitignore", "dockerignore", "editorconfig", "srt", "vtt", "nfo", "rst", "tex", "diff", "patch", "service", "desktop",
    )
    val OFFICE = setOf("docx", "doc", "odt", "rtf", "xlsx", "xls", "ods", "pptx", "potx", "ppsx", "odp", "dxf", "svg", "idml")
    val ARCHIVES = setOf("zip")

    /** Endungen mit Vorschaubild vom NAS (thumbs/render.go). */
    val THUMBS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "pdf",
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "ogv", "mts", "m2ts", "wmv",
        "docx", "xlsx", "pptx", "odt", "ods", "odp",
    )

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase().takeIf { name.contains('.') }.orEmpty()

    fun of(name: String): FileKind {
        val e = ext(name)
        return when {
            e in IMAGES -> FileKind.Image
            e in VIDEOS -> FileKind.Video
            e in AUDIO -> FileKind.Audio
            e == "pdf" -> FileKind.Pdf
            editable(name) || e in CODE -> FileKind.Text
            e in OFFICE -> FileKind.Office
            e in ARCHIVES -> FileKind.Archive
            else -> FileKind.Other
        }
    }

    /** Ob der Texteditor des NAS die Datei bearbeiten kann. */
    fun editable(name: String): Boolean = name in TEXT_NAMES || ext(name) in TEXT

    fun hasThumb(name: String): Boolean = ext(name) in THUMBS

    fun markdown(name: String): Boolean = ext(name) in setOf("md", "markdown")
}
