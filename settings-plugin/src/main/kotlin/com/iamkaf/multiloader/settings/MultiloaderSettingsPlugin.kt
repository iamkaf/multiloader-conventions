package com.iamkaf.multiloader.settings

import com.iamkaf.multiloader.support.BuildToolsVersions
import com.iamkaf.multiloader.support.ConsumerDslPolicy
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderTargetScope
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import java.io.File
import java.util.Properties

class MultiloaderSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        ConsumerDslPolicy.requireKotlinDsl(settings)
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

    private fun configurePluginRepositories(settings: Settings) =
        SettingsRepositoryPolicy.configurePluginRepositories(settings)

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
        SettingsRepositoryPolicy.configureDependencyRepositories(settings)

        val mcVersion = settings.providers.gradleProperty("project.minecraft").orNull ?: return
        val catalogCoordinate = settings.providers.gradleProperty("project.catalog-coordinate")
            .orElse(VersionPolicy.catalogCoordinate(mcVersion))
            .get()

        settings.dependencyResolutionManagement.versionCatalogs.create("libs") {
            from(catalogCoordinate)
        }
    }

    private fun configureStonecutterDependencyResolution(settings: Settings, versionDirs: List<File>) {
        SettingsRepositoryPolicy.configureDependencyRepositories(settings)

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

        val stonecutter = settings.extensions.getByName("stonecutter")
        GroovyGradleDsl.invoke(
            stonecutter,
            "create",
            settings.rootProject,
            GroovyGradleDsl.closure { tree ->
                GroovyGradleDsl.invoke(tree, "versions", *allVersions)
                configureBranch(tree, "common", allVersions)
                configureBranch(tree, "fabric", fabricVersions)
                configureBranch(tree, "forge", forgeVersions)
                configureBranch(tree, "neoforge", neoForgeVersions)
            },
        )
    }

    private fun configureBranch(tree: Any, name: String, versions: Array<String>) {
        if (versions.isEmpty()) return
        GroovyGradleDsl.invoke(
            tree,
            "branch",
            name,
            GroovyGradleDsl.closure { branch ->
                GroovyGradleDsl.invoke(branch, "versions", *versions)
            },
        )
    }

    private fun parseEnabledLoaders(settings: Settings): List<String> =
        SettingsVersionMatrix.parseEnabledLoaders(settings)

    private fun enabledLoadersByVersion(settings: Settings, versionDirs: List<File>): Map<String, List<String>> =
        SettingsVersionMatrix.enabledLoadersByVersion(versionDirs)

    private fun parseEnabledLoaders(props: Properties): List<String> =
        SettingsVersionMatrix.parseEnabledLoaders(props)

    private fun maybeInclude(settings: Settings, projectName: String) {
        val projectDir = File(settings.settingsDir, projectName)
        if (!projectDir.isDirectory) return

        val buildFile = File(projectDir, "build.gradle.kts")
        if (!buildFile.isFile) return

        settings.include(projectName)
    }

    private fun versionDirectories(settings: Settings): List<File> {
        return SettingsVersionMatrix.versionDirectories(settings)
    }

    private fun versionMetadata(settings: Settings, versionDir: File): Properties {
        return SettingsVersionMatrix.versionMetadata(versionDir)
    }

    private fun catalogCoordinate(props: Properties, mcVersion: String): String =
        SettingsVersionMatrix.catalogCoordinate(props, mcVersion)
}
