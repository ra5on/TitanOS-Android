plugins {
    alias(libs.plugins.android.library)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.ra5on.titanos.model"
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
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
