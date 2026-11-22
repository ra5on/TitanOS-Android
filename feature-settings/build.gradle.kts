import java.time.Duration

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Systemsteuerung der TitanOS-App: alles, was web/src/routes/settings kann
// (Texte und Suchindex aus dem Web, feature-settings/tools/web.mjs).
android {
    namespace = "io.github.ra5on.titanos.settings"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.maxParallelForks = 1
                // Ein hängender Bildtest darf keinen der zwei Gradle-Plätze der VM blockieren (RAHMEN.md);
                // 30 statt 10 Minuten, weil die ganze Suite mehrere hundert Aufnahmen prüft.
                it.timeout.set(Duration.ofMinutes(30))
            }
        }
    }
    lint {
        abortOnError = true
    }
}

roborazzi {
    outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
}

dependencies {
    lintChecks(project(":lint-rules"))
    api(project(":core-ui"))
    api(project(":core-net"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
