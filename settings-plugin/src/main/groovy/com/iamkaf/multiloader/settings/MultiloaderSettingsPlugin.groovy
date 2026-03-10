package com.iamkaf.multiloader.settings

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class MultiloaderSettingsPlugin implements Plugin<Settings> {

    private static final List<String> KNOWN_LOADERS = ['fabric', 'forge', 'neoforge']

    @Override
    void apply(Settings settings) {
        configurePluginRepositories(settings)
        configureDependencyResolution(settings)
        configureRootName(settings)
        includeProjects(settings)
    }

    private static void configurePluginRepositories(Settings settings) {
        settings.pluginManagement.repositories.gradlePluginPortal()
        settings.pluginManagement.repositories.mavenCentral()
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'Fabric'
            repo.url = settings.uri('https://maven.fabricmc.net')
        }
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'Forge'
            repo.url = settings.uri('https://maven.minecraftforge.net')
        }
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'Sponge'
            repo.url = settings.uri('https://repo.spongepowered.org/repository/maven-public')
        }
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'FirstDark'
            repo.url = settings.uri('https://maven.firstdark.dev/releases')
        }
    }

    private static void configureDependencyResolution(Settings settings) {
        settings.dependencyResolutionManagement.repositories.mavenLocal()
        settings.dependencyResolutionManagement.repositories.maven { repo ->
            repo.url = settings.uri('https://maven.kaf.sh')
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()

        def mcVersion = settings.providers.gradleProperty('platform_minecraft_version').orNull
        if (mcVersion == null) {
            return
        }

        def catalogCoordinate = settings.providers.gradleProperty('version_catalog_coordinate')
            .orElse("com.iamkaf.platform:mc-${mcVersion}:${mcVersion}-SNAPSHOT")
            .get()

        settings.dependencyResolutionManagement.versionCatalogs.create('libs') { libs ->
            libs.from(catalogCoordinate)
        }
    }

    private static void configureRootName(Settings settings) {
        def modName = settings.providers.gradleProperty('mod_name').orNull
        if (modName != null && !modName.isBlank()) {
            settings.rootProject.name = modName
        }
    }

    private static void includeProjects(Settings settings) {
        maybeInclude(settings, 'common')

        parseEnabledLoaders(settings).each { loader ->
            maybeInclude(settings, loader)
        }
    }

    private static List<String> parseEnabledLoaders(Settings settings) {
        def raw = settings.providers.gradleProperty('enabled_loaders')
            .orElse(settings.providers.gradleProperty('enabled_platforms'))
            .orElse('fabric,forge,neoforge')
            .get()

        return raw.split(',')
            .collect { it.trim() }
            .findAll { !it.isBlank() }
            .findAll { KNOWN_LOADERS.contains(it) }
    }

    private static void maybeInclude(Settings settings, String projectName) {
        def projectDir = new File(settings.settingsDir, projectName)
        if (!projectDir.isDirectory()) {
            return
        }

        def buildFile = new File(projectDir, 'build.gradle')
        if (!buildFile.isFile()) {
            return
        }

        settings.include(projectName)
    }
}
