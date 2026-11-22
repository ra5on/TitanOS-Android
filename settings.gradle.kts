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
    // Ablage und Werkzeuge: Speicher, Freigaben, Benutzer, Plugins, Werkzeuge (Grundlage core-admin).
    ":core-admin",
    ":core-admin-testing",
    ":feature-storage",
    ":feature-shares",
    ":feature-users",
    ":feature-plugins",
    ":feature-tools",
    ":feature-pairing",
    ":feature-home",
    ":feature-files",
    ":feature-photos",
    ":feature-update",
    ":feature-widgets",
    ":feature-notify",
    ":feature-agent",
    // Rechnen: Docker, VMs/LXC, NVR, Terminal (gemeinsame Verbindung und Texte in core-compute).
    ":core-compute",
    ":feature-terminal",
    ":feature-docker",
    ":feature-vms",
    ":feature-nvr",
    ":feature-mail",
    ":feature-pim",
    ":feature-vpn",
    ":app-titanos",
    ":app-agent",
    ":app-watch",
    ":lint-rules",
)
