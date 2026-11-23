import java.time.Duration

plugins {
    alias(libs.plugins.android.library)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.ra5on.titanos.net"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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
        checkDependencies = false
    }
}

dependencies {
    lintChecks(project(":lint-rules"))
    api(project(":core-model"))
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Ein hängender Test beendet den Lauf, statt einen der zwei Gradle-Plätze der VM zu halten.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
