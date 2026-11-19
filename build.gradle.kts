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
    val roots = subprojects.filter { it.name != "lint-rules" }.map { it.projectDir.resolve("src/main") }
    inputs.files(roots.map { fileTree(it) })
    doLast {
        val bad = Regex("""TextOverflow\.Ellipsis|\bmaxLines\b|android:ellipsize|android:singleLine|android:maxLines""")
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (bad.containsMatchIn(line)) "${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" else null
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("Abgeschnittener Text ist verboten:\n" + hits.joinToString("\n"))
        }
    }
}

subprojects {
    tasks.matching { it.name == "lint" }.configureEach { dependsOn(rootProject.tasks.named("noTruncatedText")) }
}
