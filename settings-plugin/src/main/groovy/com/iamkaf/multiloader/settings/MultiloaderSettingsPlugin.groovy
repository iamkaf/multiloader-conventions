package com.iamkaf.multiloader.settings

import dev.kikugie.stonecutter.data.tree.builder.BranchBuilder
import dev.kikugie.stonecutter.data.tree.builder.TreeBuilder
import dev.kikugie.stonecutter.settings.StonecutterSettingsExtension
import org.gradle.api.Plugin
import org.gradle.api.Action
import org.gradle.api.initialization.Settings

import java.util.Properties

class MultiloaderSettingsPlugin implements Plugin<Settings> {

    private static final List<String> KNOWN_LOADERS = ['fabric', 'forge', 'neoforge']
    private static final String STONECUTTER_VERSION = '0.7.10'

    @Override
    void apply(Settings settings) {
        configurePluginRepositories(settings)
        configureConventionPluginVersions(settings)

        def versionDirs = versionDirectories(settings)
        if (!versionDirs.isEmpty()) {
            configureStonecutterDependencyResolution(settings, versionDirs)
            configureStonecutterRootName(settings, versionDirs)
            configureStonecutterProjects(settings, versionDirs)
            return
        }

        configureFlatDependencyResolution(settings)
        configureFlatRootName(settings)
        includeFlatProjects(settings)
    }

    private static void configurePluginRepositories(Settings settings) {
        settings.pluginManagement.repositories.mavenLocal()
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
            repo.name = 'NeoForge'
            repo.url = settings.uri('https://maven.neoforged.net/releases')
        }
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'Sponge'
            repo.url = settings.uri('https://repo.spongepowered.org/repository/maven-public')
        }
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'FirstDark'
            repo.url = settings.uri('https://maven.firstdarkdev.xyz/releases')
        }
        settings.pluginManagement.repositories.maven { repo ->
            repo.name = 'Kaf Maven'
            repo.url = settings.uri('https://maven.kaf.sh')
        }
    }

    private static void configureConventionPluginVersions(Settings settings) {
        def conventionsVersion = settings.providers.gradleProperty('project.plugins').orNull

        settings.pluginManagement.plugins {
            id('dev.kikugie.stonecutter') version STONECUTTER_VERSION
            if (conventionsVersion != null && !conventionsVersion.isBlank()) {
                id('com.iamkaf.multiloader.core') version conventionsVersion
                id('com.iamkaf.multiloader.common') version conventionsVersion
                id('com.iamkaf.multiloader.fabric') version conventionsVersion
                id('com.iamkaf.multiloader.forge') version conventionsVersion
                id('com.iamkaf.multiloader.neoforge') version conventionsVersion
                id('com.iamkaf.multiloader.publishing') version conventionsVersion
                id('com.iamkaf.multiloader.translations') version conventionsVersion
                id('com.iamkaf.multiloader.root') version conventionsVersion
            }
        }
    }

    private static void configureFlatDependencyResolution(Settings settings) {
        settings.dependencyResolutionManagement.repositories.mavenLocal()
        settings.dependencyResolutionManagement.repositories.maven { repo ->
            repo.url = settings.uri('https://maven.kaf.sh')
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()

        def mcVersion = settings.providers.gradleProperty('project.minecraft').orNull
        if (mcVersion == null) {
            return
        }

        def catalogCoordinate = settings.providers.gradleProperty('project.catalog-coordinate')
            .orElse("com.iamkaf.platform:mc-${mcVersion}:${mcVersion}-SNAPSHOT")
            .get()

        settings.dependencyResolutionManagement.versionCatalogs.create('libs') { libs ->
            libs.from(catalogCoordinate)
        }
    }

    private static void configureStonecutterDependencyResolution(Settings settings, List<File> versionDirs) {
        settings.dependencyResolutionManagement.repositories.mavenLocal()
        settings.dependencyResolutionManagement.repositories.maven { repo ->
            repo.url = settings.uri('https://maven.kaf.sh')
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()

        versionDirs.each { dir ->
            settings.dependencyResolutionManagement.versionCatalogs.create(catalogName(dir.name)) { libs ->
                libs.from(settings.layout.settingsDirectory.files("../version-catalog/mc-${dir.name}/gradle/libs.versions.toml"))
            }
        }
    }

    private static void configureFlatRootName(Settings settings) {
        def modName = settings.providers.gradleProperty('mod.name').orNull
        if (modName != null && !modName.isBlank()) {
            settings.rootProject.name = modName
        }
    }

    private static void configureStonecutterRootName(Settings settings, List<File> versionDirs) {
        def modName = settings.providers.gradleProperty('mod.name').orNull
        if (modName != null && !modName.isBlank()) {
            settings.rootProject.name = modName
            return
        }

        def firstVersionProps = loadProperties(new File(versionDirs.first(), 'gradle.properties'))
        settings.rootProject.name = firstVersionProps.getProperty('mod.name', 'Template')
    }

    private static void includeFlatProjects(Settings settings) {
        maybeInclude(settings, 'common')

        parseEnabledLoaders(settings).each { loader ->
            maybeInclude(settings, loader)
        }
    }

    private static void configureStonecutterProjects(Settings settings, List<File> versionDirs) {
        def versionsWithLoaders = [:] as LinkedHashMap<String, List<String>>
        versionDirs.each { dir ->
            def props = loadProperties(new File(dir, 'gradle.properties'))
            def loaders = parseEnabledLoaders(props)
            if (loaders.isEmpty()) {
                throw new IllegalStateException("No enabled loaders configured for ${dir.name}")
            }
            versionsWithLoaders[dir.name] = loaders
        }

        def uniqueVersions = versionsWithLoaders.keySet().toList()
        def fabricVersions = versionsWithLoaders.findAll { it.value.contains('fabric') }.keySet().toList()
        def forgeVersions = versionsWithLoaders.findAll { it.value.contains('forge') }.keySet().toList()
        def neoforgeVersions = versionsWithLoaders.findAll { it.value.contains('neoforge') }.keySet().toList()
        def allVersions = uniqueVersions.toArray(new String[0])
        def fabricVersionArray = fabricVersions.toArray(new String[0])
        def forgeVersionArray = forgeVersions.toArray(new String[0])
        def neoforgeVersionArray = neoforgeVersions.toArray(new String[0])

        settings.extensions.configure(StonecutterSettingsExtension, new Action<StonecutterSettingsExtension>() {
            @Override
            void execute(StonecutterSettingsExtension stonecutter) {
                stonecutter.create(settings.rootProject, new Action<TreeBuilder>() {
                    @Override
                    void execute(TreeBuilder tree) {
                        tree.versions(allVersions)
                        configureBranch(tree, 'common', allVersions)
                        configureBranch(tree, 'fabric', fabricVersionArray)
                        configureBranch(tree, 'forge', forgeVersionArray)
                        configureBranch(tree, 'neoforge', neoforgeVersionArray)
                    }
                })
            }
        })
    }

    private static void configureBranch(TreeBuilder tree, String name, String[] versions) {
        if (versions.length == 0) {
            return
        }

        tree.branch(name, { BranchBuilder branch ->
            branch.versions(versions)
        })
    }

    private static List<String> parseEnabledLoaders(Settings settings) {
        def raw = settings.providers.gradleProperty('project.enabled-loaders')
            .orElse('fabric,forge,neoforge')
            .get()

        parseEnabledLoaders(parseInlineProperties(raw))
    }

    private static List<String> parseEnabledLoaders(Properties props) {
        props.getProperty('project.enabled-loaders', '')
            .split(',')
            .collect { it.trim() }
            .findAll { !it.isBlank() }
            .findAll { KNOWN_LOADERS.contains(it) }
    }

    private static Properties parseInlineProperties(String loaders) {
        def properties = new Properties()
        properties.setProperty('project.enabled-loaders', loaders)
        properties
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

    private static List<File> versionDirectories(Settings settings) {
        def versionsDir = new File(settings.settingsDir, 'versions')
        if (!versionsDir.isDirectory()) {
            return []
        }

        versionsDir.listFiles()
            ?.findAll { candidate -> new File(candidate, 'gradle.properties').isFile() }
            ?.sort { left, right -> left.name <=> right.name }
            ?: []
    }

    private static Properties loadProperties(File file) {
        def properties = new Properties()
        file.withInputStream(properties.&load)
        properties
    }

    private static String catalogName(String minecraftVersion) {
        "libsMc${minecraftVersion.replace('.', '').replace('-', '')}"
    }
}
