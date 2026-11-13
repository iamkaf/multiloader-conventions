pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.kaf.sh")
        }
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.fabricmc.net")
        }
    }
    plugins {
        id("org.gradle.kotlin.kotlin-dsl") version "6.7.3"
    }
}

plugins {
    // Provisions the Java 25 daemon that Amber Loom needs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.kaf.sh")
        }
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("buildTools") {
            from(files("catalogs/build-tools/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "multiloader-conventions"

include("conventions-support")
include("core-plugin")
include("settings-plugin")
include("root-plugin")
include("common-plugin")
include("platform-plugin")
include("fabric-plugin")
include("forge-plugin")
include("neoforge-plugin")
include("paper-plugin")
include("translations-plugin")
include("publishing-plugin")
