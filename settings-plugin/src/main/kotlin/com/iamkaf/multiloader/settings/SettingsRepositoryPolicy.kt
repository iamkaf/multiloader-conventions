package com.iamkaf.multiloader.settings

import org.gradle.api.initialization.Settings
import java.net.URI

object SettingsRepositoryPolicy {
    fun configurePluginRepositories(settings: Settings) {
        settings.pluginManagement.repositories.mavenLocal()
        settings.pluginManagement.repositories.gradlePluginPortal()
        settings.pluginManagement.repositories.mavenCentral()
        settings.pluginManagement.repositories.maven {
            name = "Fabric"
            url = URI.create("https://maven.fabricmc.net")
        }
        settings.pluginManagement.repositories.maven {
            name = "Forge"
            url = URI.create("https://maven.minecraftforge.net")
        }
        settings.pluginManagement.repositories.maven {
            name = "NeoForge"
            url = URI.create("https://maven.neoforged.net/releases")
        }
        settings.pluginManagement.repositories.maven {
            name = "Sponge"
            url = URI.create("https://repo.spongepowered.org/repository/maven-public")
        }
        settings.pluginManagement.repositories.maven {
            name = "FirstDark"
            url = URI.create("https://maven.firstdarkdev.xyz/releases")
        }
        settings.pluginManagement.repositories.maven {
            name = "Kaf Maven"
            url = URI.create("https://maven.kaf.sh")
        }
    }

    fun configureDependencyRepositories(settings: Settings) {
        settings.dependencyResolutionManagement.repositories.mavenLocal()
        settings.dependencyResolutionManagement.repositories.maven {
            url = URI.create("https://maven.kaf.sh")
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()
    }
}
