package com.iamkaf.multiloader.settings

import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import java.net.URI

object SettingsRepositoryPolicy {
    fun configurePluginRepositories(settings: Settings) {
        removePluginRepository(settings, "https://maven.kikugie.dev/snapshots")

        settings.pluginManagement.repositories.withType(MavenArtifactRepository::class.java).configureEach {
            configurePluginRepositoryContent(this)
        }

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
            name = "Kaf Maven"
            url = URI.create("https://maven.kaf.sh")
            content {
                includeGroupByRegex("com\\.iamkaf(\\..*)?")
            }
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()
    }

    private fun configurePluginRepositoryContent(repository: MavenArtifactRepository) {
        when (repository.url.normalized()) {
            "https://maven.kaf.sh" -> repository.content {
                includeGroupByRegex("com\\.iamkaf(\\..*)?")
            }

            "https://maven.fabricmc.net" -> repository.content {
                includeGroup("fabric-loom")
                includeGroupByRegex("net\\.fabricmc(\\..*)?")
                includeGroup("net.fabricmc.fabric-loom")
            }

            "https://maven.minecraftforge.net" -> repository.content {
                includeGroup("net.minecraftforge")
                includeGroup("net.minecraftforge.gradle")
            }

            "https://maven.neoforged.net/releases" -> repository.content {
                includeGroupByRegex("net\\.neoforged(\\..*)?")
            }

            "https://repo.spongepowered.org/repository/maven-public" -> repository.content {
                includeGroupByRegex("org\\.spongepowered(\\..*)?")
            }

            "https://maven.firstdarkdev.xyz/releases" -> repository.content {
                includeGroupByRegex("me\\.hypherionmc(\\..*)?")
            }
        }
    }

    private fun removePluginRepository(settings: Settings, url: String) {
        settings.pluginManagement.repositories
            .withType(MavenArtifactRepository::class.java)
            .matching { it.url.normalized() == url.trimEnd('/') }
            .toList()
            .forEach { settings.pluginManagement.repositories.remove(it) }
    }

    private fun URI.normalized(): String =
        toString().trimEnd('/')
}
