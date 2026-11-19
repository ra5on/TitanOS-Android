plugins {
    alias(libs.plugins.android.application)
}

// PLAN 116: die Agent-App ist in dieser Testfassung nur eine leere Hülle.
android {
    namespace = "io.github.ra5on.titanos.agent"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.ra5on.titanos.agent"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.01"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
    }
}

dependencies {
    lintChecks(project(":lint-rules"))
}
