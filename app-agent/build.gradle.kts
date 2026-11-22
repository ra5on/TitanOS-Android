plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release-Signatur wie bei der TitanOS-App: derselbe Schlüssel, nur über
// Umgebungsvariablen (der Inhaber signiert selbst). Ohne sie bleibt der
// Release-Build unsigniert; Debug-Builds wie üblich.
val releaseKeystore: String? = System.getenv("TITANOS_KEYSTORE")?.takeIf { it.isNotBlank() }
val releasePassword: String? = System.getenv("TITANOS_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }

// PLAN 116: die Agent-App (Chat mit dem KI-Agenten des NAS).
android {
    namespace = "io.github.ra5on.titanos.agent"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.ra5on.titanos.agent"
        minSdk = 33
        targetSdk = 37
        versionCode = 100
        versionName = "1.00"
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
    lint {
        abortOnError = true
    }
}

dependencies {
    lintChecks(project(":lint-rules"))
    implementation(project(":core-ui"))
    implementation(project(":core-net"))
    implementation(project(":feature-pairing"))
    implementation(project(":feature-agent"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
