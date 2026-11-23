import java.time.Duration

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.ra5on.titanos.shares"
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
    implementation(project(":core-admin"))
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core-admin-testing"))
    debugImplementation(libs.compose.ui.test.manifest)
}

// Ein hängender Bildtest (endlose Animation, Zeichnen) beendet den Lauf, statt einen der zwei Gradle-Plätze der VM zu halten.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
