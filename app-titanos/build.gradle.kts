plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
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
        versionCode = 3
        versionName = "0.03"
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
    implementation(project(":feature-pairing"))
    implementation(project(":feature-home"))
    implementation(project(":feature-files"))
    implementation(project(":feature-update"))
    implementation(project(":feature-widgets"))
    implementation(libs.androidx.glance)
    implementation(project(":feature-photos"))
    implementation(project(":feature-notify"))
    implementation(project(":feature-mail"))
    implementation(project(":feature-pim"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
