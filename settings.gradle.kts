pluginManagement {
    repositories {
        maven { url = uri("offline-repository") }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.openjfx.javafxplugin") version "0.1.0"
    }
}

rootProject.name = "jw365"
