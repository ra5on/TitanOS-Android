plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Nur für Tests von „Ablage und Werkzeuge“: nachgebautes NAS (MockWebServer mit
// den Antworten der Web-Testdaten) und die Prüfung der Bildtests (abgeschnittene
// Texte, Platzhalter, Bedienelemente ohne Namen).
android {
    namespace = "io.github.ra5on.titanos.admin.testing"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        abortOnError = true
    }
}

dependencies {
    api(project(":core-admin"))
    api(libs.junit)
    api(libs.okhttp.mockwebserver)
    api(libs.kotlinx.coroutines.test)
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.rule)
    api(libs.compose.ui.test.junit4)
    api(libs.androidx.test.core)
}
