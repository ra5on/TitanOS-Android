package io.github.ra5on.titanos.plugins

// Die Regeln der Plugin-Seite des Webs (plugins.ts, plugin-progress.ts,
// DriverChoice.svelte, cleanup/+page.svelte) ohne Oberfläche.

/** Der Text in der Sprache der Oberfläche, sonst Englisch, sonst irgendeiner. */
internal fun localized(texts: Map<String, String>?, lang: String): String =
    texts?.get(lang) ?: texts?.get("en") ?: texts?.values?.firstOrNull() ?: ""

internal fun containsOf(p: Plugin, lang: String): List<String> = p.contains?.get(lang) ?: p.contains?.get("en") ?: emptyList()

internal fun aboutOf(p: Plugin, lang: String): Details? = p.details?.get(lang) ?: p.details?.get("en")

/** Zwei Zeichen des Titels für die Kachel des Plugins. */
internal fun monogram(p: Plugin): String {
    val clean = p.title.filter { it.isLetterOrDigit() && it.code < 128 }.take(2)
    return if (clean.isEmpty()) "?" else clean.replaceFirstChar { it.uppercaseChar() }
}

/** Ein fester Farbton je Plugin-Name (0–359). */
internal fun hue(p: Plugin): Int = p.name.fold(7) { sum, c -> (sum * 31 + c.code) % 360 }

/** Bereiche des Katalogs in dieser Reihenfolge; unbekannte sind „other“. */
internal val CATEGORIES = listOf("network", "graphics", "sensors", "storage", "tools", "remote", "other")

internal fun categoryOf(category: String?): String = if (category in CATEGORIES) category!! else "other"

/** Ob jedes Wort (klein) in Titel, Name, Beschreibung, Hardware oder Geräten vorkommt. */
internal fun matches(p: Plugin, words: String, lang: String): Boolean {
    if (words.isBlank()) return true
    val info = aboutOf(p, lang)
    val hay = (
        listOf(p.title, p.name, localized(p.description, lang)) + info?.hardware.orEmpty() + listOf(info?.`when`.orEmpty()) +
            p.devices.orEmpty().map { it.name }
        ).joinToString(" ").lowercase()
    return words.trim().lowercase().split(Regex("\\s+")).all { hay.contains(it) }
}

/** Ein Weblink, der als Link gezeigt werden darf. */
internal fun isLink(value: String) = Regex("^https://\\S+$").matches(value)

internal enum class Badge { Incompatible, Available, NotLoaded, Active, Waiting, Off }

internal fun badge(p: Plugin): Badge = when {
    !p.installed && p.problem == "kernel" -> Badge.Incompatible
    !p.installed -> Badge.Available
    p.problem.isNotEmpty() -> Badge.NotLoaded
    p.active -> Badge.Active
    p.enabled -> Badge.Waiting
    else -> Badge.Off
}

/** Der neueste Vorgang für ein Plugin, laufende zuerst. */
internal fun latestFor(list: List<PluginProgress>, plugin: String): PluginProgress? {
    val mine = list.filter { it.plugin == plugin }
    return mine.lastOrNull { it.state == "running" } ?: mine.lastOrNull()
}

/** Prozent des Downloads nur, solange er mit bekannter Größe läuft. */
internal fun downloadPercent(p: PluginProgress): Double? {
    val step = p.steps.firstOrNull { it.id == "download" }
    if (step?.state != "running" || p.total <= 0) return null
    return (p.bytes.toDouble() / p.total * 100).coerceIn(0.0, 100.0)
}

/** Ältere Reihen als die mitgelieferte bauen mit neuen Kerneln oft nicht mehr. */
internal fun oldDriver(version: String, bundled: String?): Boolean {
    if (bundled.isNullOrEmpty()) return false
    val a = version.substringBefore('.').toIntOrNull() ?: return false
    val b = bundled.substringBefore('.').toIntOrNull() ?: return false
    return a < b
}

internal val DRIVER_WORKING = setOf("checking", "downloading", "environment", "building")

/** Platz, den die gewählten, nicht geschützten Sätze freigeben. */
internal fun cleanupTotal(releases: List<Release>, selected: Set<String>): Long =
    releases.filter { it.version in selected && !it.protected }.sumOf { it.bytes }
