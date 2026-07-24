pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK toolchain if it isn't already installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = providers.gradleProperty("plugin_name").get()
