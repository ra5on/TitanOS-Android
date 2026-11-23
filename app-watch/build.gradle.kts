import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Release-Signatur wie bei den Handy-Apps: derselbe Schlüssel, nur über
// Umgebungsvariablen in der CI (Umgebung „release“). Ohne sie bleibt der
// Release-Build unsigniert.
val releaseKeystore: String? = System.getenv("TITANOS_KEYSTORE")?.takeIf { it.isNotBlank() }
val releasePassword: String? = System.getenv("TITANOS_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }

// PLAN 116 Paket G: die Uhr-App für Wear OS, ohne Google (keine Play-Dienste,
// keine Wearable Data Layer API). Sie spricht selbst mit dem NAS.
android {
    namespace = "io.github.ra5on.titanos.watch"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.ra5on.titanos.watch"
        // Wie die Handy-Apps: ab Android 13, also Wear OS 4.
        minSdk = 33
        targetSdk = 37
        versionCode = 101
        versionName = "1.01"
    }
    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = "titanos-release"
                keyPassword = releasePassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation(project(":core-ui"))
    implementation(project(":core-net"))
    implementation(project(":feature-update"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications)
    implementation(libs.concurrent.futures.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Ein hängender Bildtest blockiert sonst einen der zwei Gradle-Plätze der VM.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(10)) }
