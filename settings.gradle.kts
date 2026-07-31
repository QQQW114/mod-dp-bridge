pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mod-dp-bridge"

include(
    "bridge-model",
    "bridge-source-index",
    "bridge-java-static",
    "bridge-target-api",
    "bridge-converter",
    "bridge-target-1597",
    "bridge-cli",
    "bridge-web",
    "bridge-runtime-extractor",
)
