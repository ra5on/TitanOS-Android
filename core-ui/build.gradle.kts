import java.time.Duration

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)

}

android {
    namespace = "io.github.ra5on.titanos.ui"
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
                it.maxHeapSize = "1536m"
                it.maxParallelForks = 1
            }
        }
    }
    lint {
        abortOnError = true
    }
}

dependencies {
    lintChecks(project(":lint-rules"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    api(libs.androidx.activity.compose)
    // Faltbare: Buch- und Tischhaltung (Fold.kt).
    implementation(libs.androidx.window)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // Wischen und Meldungen (Swipe.kt) als Compose-Tests in Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Ein hängender Test beendet den Lauf, statt einen der zwei Gradle-Plätze der VM zu halten.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
