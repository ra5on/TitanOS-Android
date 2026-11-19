pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TitanOS-Android"

include(
    ":core-model",
    ":core-net",
    ":core-ui",
    ":feature-pairing",
    ":feature-home",
    ":feature-files",
    ":feature-photos",
    ":app-titanos",
    ":app-agent",
    ":lint-rules",
)
