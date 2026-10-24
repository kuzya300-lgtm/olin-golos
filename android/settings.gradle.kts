pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "govorun-lite"
include(":app")
include(":product-ui")
providers.gradleProperty("govorunSettingsExtension").orNull?.let { apply(from = it) }
