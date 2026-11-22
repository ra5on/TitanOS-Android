import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

// Release-Signatur nur über Umgebungsvariablen (der Inhaber signiert selbst).
// Ohne sie bleibt der Release-Build unsigniert; Debug-Builds wie üblich.
val releaseKeystore: String? = System.getenv("TITANOS_KEYSTORE")?.takeIf { it.isNotBlank() }
val releasePassword: String? = System.getenv("TITANOS_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.ra5on.titanos"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.ra5on.titanos"
        minSdk = 33
        targetSdk = 37
        versionCode = 100
        versionName = "1.00"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // Bauvarianten (PLAN 116, Inhaber 21.11.2026): „github“ wie bisher mit Selbst-Update und Release auf
    // GitHub; „play“ für Google Play ohne Selbst-Update, mit den Abweichungen aus den Play-Richtlinien
    // (src/play: Manifest, R.bool.titan_play). Veröffentlicht wird nur github; play entsteht als AAB.
    // versionName/versionCode stehen nur in defaultConfig (release.yml liest sie dort).
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
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
    packaging {
        jniLibs {
            // Aus com.wireguard.android:tunnel nur libwg-go.so (GoBackend). libwg.so und libwg-quick.so sind
            // wireguard-tools (GPL-2.0) für den Kernel-Weg mit Root, den die App nicht nutzt (THIRD_PARTY_LICENSES).
            excludes += listOf("**/libwg.so", "**/libwg-quick.so")
        }
    }
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
    // WebRTC (Gegensprechen im NVR) nur für 64-Bit-Geräte: arm64 (Handys, Tablets)
    // und x86_64 (Chromebooks, Emulator); die 32-Bit-Kopien machten das APK ~10 MB größer.
    packaging {
        jniLibs {
            excludes += listOf("lib/armeabi-v7a/libjingle_peerconnection_so.so", "lib/x86/libjingle_peerconnection_so.so")
        }
    }
}

roborazzi {
    outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
}

dependencies {
    lintChecks(project(":lint-rules"))
    implementation(project(":core-ui"))
    implementation(project(":core-net"))
    implementation(project(":feature-pairing"))
    implementation(project(":feature-home"))
    implementation(project(":feature-files"))
    // Ablage und Werkzeuge (Admin.kt).
    implementation(project(":core-admin"))
    implementation(project(":feature-storage"))
    implementation(project(":feature-shares"))
    implementation(project(":feature-users"))
    implementation(project(":feature-plugins"))
    implementation(project(":feature-tools"))
    // Selbst-Update nur von GitHub; die Play-Variante hat stattdessen src/play/…/update/PlayAbout.kt.
    "githubImplementation"(project(":feature-update"))
    implementation(project(":feature-widgets"))
    implementation(libs.androidx.glance)
    implementation(project(":feature-photos"))
    implementation(project(":feature-notify"))
    implementation(project(":core-compute"))
    implementation(project(":feature-terminal"))
    implementation(project(":feature-docker"))
    implementation(project(":feature-vms"))
    implementation(project(":feature-nvr"))
    implementation(project(":feature-mail"))
    implementation(project(":feature-pim"))
    implementation(project(":feature-vpn"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-account"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Ein hängender Bildtest beendet den Lauf, statt einen der zwei Gradle-Plätze der VM zu halten (viele Bildtests: 30 Minuten).
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(30))
}
