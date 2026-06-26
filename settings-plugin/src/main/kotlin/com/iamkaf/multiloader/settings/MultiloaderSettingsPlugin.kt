package com.iamkaf.multiloader.settings

import com.iamkaf.multiloader.support.BuildToolsVersions
import com.iamkaf.multiloader.support.MultiloaderTargetScope
import com.iamkaf.multiloader.support.VersionPolicy
import dev.kikugie.stonecutter.settings.StonecutterSettingsExtension
import dev.kikugie.stonecutter.settings.tree.TreeBuilder
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import java.io.File
import java.net.URI
import java.util.Properties

class MultiloaderSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        configurePluginRepositories(settings)
        configureConventionPluginVersions(settings)
        applySettingsPlugins(settings)

        val versionDirs = versionDirectories(settings)
        if (versionDirs.isNotEmpty()) {
            val enabledLoadersByVersion = enabledLoadersByVersion(settings, versionDirs)
            val targetScope = MultiloaderTargetScope.fromSettings(settings, enabledLoadersByVersion)
            val includedVersionDirs = versionDirs.filter { targetScope.includesVersion(it.name) }

            configureStonecutterDependencyResolution(settings, includedVersionDirs)
            configureStonecutterRootName(settings, includedVersionDirs)
            configureStonecutterProjects(settings, includedVersionDirs, targetScope)
            return
        }

        configureFlatDependencyResolution(settings)
        configureFlatRootName(settings)
        includeFlatProjects(settings)
    }

    private fun configurePluginRepositories(settings: Settings) {
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

    private fun configureConventionPluginVersions(settings: Settings) {
        val conventionsVersion = settings.providers.gradleProperty("project.plugins").orNull
        val plugins = settings.pluginManagement.plugins

        plugins.id("org.gradle.toolchains.foojay-resolver-convention")
            .version(BuildToolsVersions.required("foojayResolverConventionPlugin"))
        plugins.id("dev.kikugie.stonecutter")
            .version(BuildToolsVersions.required("stonecutterPlugin"))
        plugins.id("fabric-loom")
            .version(BuildToolsVersions.required("fabricLoomPlugin"))
        plugins.id("net.fabricmc.fabric-loom")
            .version(BuildToolsVersions.required("fabricLoomPlugin"))
        plugins.id("net.neoforged.moddev")
            .version(BuildToolsVersions.required("neoforgeModDevPlugin"))
        plugins.id("net.neoforged.moddev.legacyforge")
            .version(BuildToolsVersions.required("neoforgeLegacyForgePlugin"))
        plugins.id("net.minecraftforge.gradle")
            .version(BuildToolsVersions.required("forgeGradlePlugin"))

        if (!conventionsVersion.isNullOrBlank()) {
            listOf(
                "com.iamkaf.multiloader.core",
                "com.iamkaf.multiloader.common",
                "com.iamkaf.multiloader.fabric",
                "com.iamkaf.multiloader.forge",
                "com.iamkaf.multiloader.neoforge",
                "com.iamkaf.multiloader.publishing",
                "com.iamkaf.multiloader.translations",
                "com.iamkaf.multiloader.root",
            ).forEach { id ->
                plugins.id(id).version(conventionsVersion)
            }
        }
    }

    private fun applySettingsPlugins(settings: Settings) {
        settings.pluginManager.apply("org.gradle.toolchains.foojay-resolver-convention")
        settings.pluginManager.apply("dev.kikugie.stonecutter")
    }

    private fun configureFlatDependencyResolution(settings: Settings) {
        settings.dependencyResolutionManagement.repositories.mavenLocal()
        settings.dependencyResolutionManagement.repositories.maven {
            url = URI.create("https://maven.kaf.sh")
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()

        val mcVersion = settings.providers.gradleProperty("project.minecraft").orNull ?: return
        val catalogCoordinate = settings.providers.gradleProperty("project.catalog-coordinate")
            .orElse(VersionPolicy.catalogCoordinate(mcVersion))
            .get()

        settings.dependencyResolutionManagement.versionCatalogs.create("libs") {
            from(catalogCoordinate)
        }
    }

    private fun configureStonecutterDependencyResolution(settings: Settings, versionDirs: List<File>) {
        settings.dependencyResolutionManagement.repositories.mavenLocal()
        settings.dependencyResolutionManagement.repositories.maven {
            url = URI.create("https://maven.kaf.sh")
        }
        settings.dependencyResolutionManagement.repositories.mavenCentral()

        versionDirs.forEach { dir ->
            val catalogCoordinate = catalogCoordinate(versionMetadata(settings, dir), dir.name)
            settings.dependencyResolutionManagement.versionCatalogs.create(VersionPolicy.catalogName(dir.name)) {
                from(catalogCoordinate)
            }
        }
    }

    private fun configureFlatRootName(settings: Settings) {
        val modName = settings.providers.gradleProperty("mod.name").orNull
        if (!modName.isNullOrBlank()) {
            settings.rootProject.name = modName
        }
    }

    private fun configureStonecutterRootName(settings: Settings, versionDirs: List<File>) {
        val modName = settings.providers.gradleProperty("mod.name").orNull
        if (!modName.isNullOrBlank()) {
            settings.rootProject.name = modName
            return
        }

        val firstVersionProps = versionMetadata(settings, versionDirs.first())
        settings.rootProject.name = firstVersionProps.getProperty("mod.name", "Template")
    }

    private fun includeFlatProjects(settings: Settings) {
        maybeInclude(settings, "common")
        parseEnabledLoaders(settings).forEach { loader ->
            maybeInclude(settings, loader)
        }
    }

    private fun configureStonecutterProjects(
        settings: Settings,
        versionDirs: List<File>,
        targetScope: MultiloaderTargetScope,
    ) {
        val versionsWithLoaders = linkedMapOf<String, List<String>>()
        versionDirs.forEach { dir ->
            val props = versionMetadata(settings, dir)
            val loaders = parseEnabledLoaders(props)
            if (loaders.isEmpty()) {
                throw IllegalStateException("No enabled loaders configured for ${dir.name}")
            }
            versionsWithLoaders[dir.name] = targetScope.loadersFor(dir.name)
        }

        val uniqueVersions = versionsWithLoaders.keys.toList()
        val fabricVersions = versionsWithLoaders.filterValues { "fabric" in it }.keys.toTypedArray()
        val forgeVersions = versionsWithLoaders.filterValues { "forge" in it }.keys.toTypedArray()
        val neoForgeVersions = versionsWithLoaders.filterValues { "neoforge" in it }.keys.toTypedArray()
        val allVersions = uniqueVersions.toTypedArray()

        settings.extensions.configure(StonecutterSettingsExtension::class.java) {
            create(settings.rootProject) {
                versions(*allVersions)
                configureBranch(this, "common", allVersions)
                configureBranch(this, "fabric", fabricVersions)
                configureBranch(this, "forge", forgeVersions)
                configureBranch(this, "neoforge", neoForgeVersions)
            }
        }
    }

    private fun configureBranch(tree: TreeBuilder, name: String, versions: Array<String>) {
        if (versions.isEmpty()) return
        tree.branch(name) {
            versions(*versions)
        }
    }

    private fun parseEnabledLoaders(settings: Settings): List<String> {
        val raw = settings.providers.gradleProperty("project.enabled-loaders")
            .orElse("fabric,forge,neoforge")
            .get()
        val props = Properties()
        props.setProperty("project.enabled-loaders", raw)
        return parseEnabledLoaders(props)
    }

    private fun enabledLoadersByVersion(settings: Settings, versionDirs: List<File>): Map<String, List<String>> =
        versionDirs.associate { dir -> dir.name to parseEnabledLoaders(versionMetadata(settings, dir)) }

    private fun parseEnabledLoaders(props: Properties): List<String> =
        props.getProperty("project.enabled-loaders", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { LoaderIdOrNull(it) != null }

    private fun LoaderIdOrNull(value: String) = com.iamkaf.multiloader.support.LoaderId.parse(value)

    private fun maybeInclude(settings: Settings, projectName: String) {
        val projectDir = File(settings.settingsDir, projectName)
        if (!projectDir.isDirectory) return

        val buildFile = File(projectDir, "build.gradle")
        if (!buildFile.isFile) return

        settings.include(projectName)
    }

    private fun versionDirectories(settings: Settings): List<File> {
        val versionsDir = File(settings.settingsDir, "versions")
        if (!versionsDir.isDirectory) return emptyList()

        return versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun versionMetadata(settings: Settings, versionDir: File): Properties {
        val metadataFile = File(versionDir, "gradle.properties")
        if (metadataFile.isFile) {
            return loadProperties(metadataFile)
        }

        val props = Properties()
        props.setProperty("project.enabled-loaders", VersionPolicy.enabledLoaderIds(versionDir.name))
        return props
    }

    private fun loadProperties(file: File): Properties =
        Properties().also { properties ->
            file.inputStream().use(properties::load)
        }

    private fun catalogCoordinate(props: Properties, mcVersion: String): String =
        props.getProperty("project.catalog-coordinate")
            ?.takeIf { it.isNotBlank() }
            ?: VersionPolicy.catalogCoordinate(mcVersion)
}
