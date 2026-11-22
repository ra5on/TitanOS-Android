plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Kein abgeschnittener Text (TitanOS CLAUDE.md, „Oberfläche“): Neben der
// Lint-Regel in :lint-rules prüft diese Aufgabe den Quelltext direkt, damit
// auch Code ohne Lint-Lauf (oder Ausdrücke, die Lint nicht auflöst) auffällt.
val noTruncatedText by tasks.registering {
    group = "verification"
    description = "Verbietet TextOverflow.Ellipsis, maxLines und ellipsize im App-Code."
    // Auch die Quellen der Bauvarianten (github, play).
    val roots = subprojects.filter { it.name != "lint-rules" }.flatMap { p -> listOf("src/main", "src/github", "src/play").map { p.projectDir.resolve(it) } }.filter { it.exists() }
    inputs.files(roots.map { fileTree(it) })
    doLast {
        // Keine Silbentrennung mitten im Wort (Inhaber 21.11.2026 „Ganze Wörter“): auch Hyphens.Auto
        // und hyphenationFrequency sind verboten; lange Wörter brechen über breakAtHyphens/breakIds (core-ui).
        val bad = Regex("""TextOverflow\.Ellipsis|\bmaxLines\b|android:ellipsize|android:singleLine|android:maxLines|Hyphens\.Auto|hyphenationFrequency""")
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (bad.containsMatchIn(line)) "${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" else null
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("Abgeschnittener Text oder Silbentrennung ist verboten:\n" + hits.joinToString("\n"))
        }
    }
}

subprojects {
    // RAHMEN (22.11.2026): jeder Testlauf hat eine Zeitgrenze; Module mit eigener Grenze setzen sie danach selbst.
    tasks.withType<Test>().configureEach { timeout.set(java.time.Duration.ofMinutes(15)) }
    tasks.matching { it.name == "lint" }.configureEach { dependsOn(rootProject.tasks.named("noTruncatedText")) }
}
