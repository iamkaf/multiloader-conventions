package com.iamkaf.multiloader.fabric

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.JavaProjectWiring
import com.iamkaf.multiloader.support.MavenPublicationWiring
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.MultiloaderProjectRole
import com.iamkaf.multiloader.support.ProjectIdentity
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import com.iamkaf.multiloader.support.adapters.FabricLoomAdapter
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderFabricPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "multiloaderFabric",
            MultiloaderFabricExtension::class.java,
        )

        if (!isStonecutterFabricProject(project)) {
            applyFlatFabricPlugin(project, extension)
            return
        }

        applyStonecutterFabricPlugin(project, extension)
    }

    private fun applyFlatFabricPlugin(project: Project, extension: MultiloaderFabricExtension) {
        project.pluginManager.apply(MultiloaderPlatformPlugin::class.java)
        val loomPluginId = if (ConventionSupport.isUnobfuscatedMinecraft(project)) {
            "net.fabricmc.fabric-loom"
        } else {
            "fabric-loom"
        }
        project.pluginManager.apply(loomPluginId)

        project.pluginManager.withPlugin(loomPluginId) {
            ConventionSupport.configureFabricBaseDependencies(project)
            configureFabricDatagen(project, extension)

            val loom = project.extensions.getByName("loom")
            val accessWidener = ConventionSupport.commonFile(
                project,
                "src/main/resources/${ConventionSupport.requiredProperty(project, "mod.id")}.accesswidener",
            )
            FabricLoomAdapter.configureLoom(project, ConventionSupport.requiredProperty(project, "mod.id"), accessWidener)
        }
    }

    private fun applyStonecutterFabricPlugin(project: Project, extension: MultiloaderFabricExtension) {
        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val context = MultiloaderProjectContext.of(project)
        val minecraftVersion = context.requiredProperty("project.minecraft")
        val catalog = context.catalogFor(minecraftVersion)
        val identity = ProjectIdentity.from(context, MultiloaderProjectRole.FABRIC)
        val useUnobfuscatedMinecraft = context.useUnobfuscatedMinecraft(minecraftVersion)
        val useLegacyFabricApiModules = VersionPolicy.usesLegacyFabricApiModules(minecraftVersion)
        val hasParchment = context.versionOrNull(catalog, "parchment") != null
        val modMenuVersion = context.versionOrNull(catalog, "modmenu")
        val hasModMenu = modMenuVersion != null && modMenuVersion != "null"
        val teaKitVersion = context.versionOrNull(catalog, "teakit")
        val teaKitLibrary = catalog.findLibrary("teakit-fabric")
        val hasTeaKit = teaKitLibrary.isPresent && teaKitVersion != null && teaKitVersion != "null"
        val useTeaKit = project.providers.systemProperty("${identity.modId}.withTeaKit")
            .orElse(project.providers.gradleProperty("${identity.modId}.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get() && identity.modId != "teakit"
        val commonProject = project.project(":common:$minecraftVersion")
        val versionDir = project.rootProject.file("versions/$minecraftVersion")
        val versionAccessWidener = versionDir.toPath()
            .resolve("common/src/main/resources/${identity.modId}.accesswidener")
            .toFile()
        val accessWidener = if (versionAccessWidener.exists()) {
            versionAccessWidener
        } else {
            project.rootProject.file("common/src/main/resources/${identity.modId}.accesswidener")
        }
        val loomPluginId = if (useUnobfuscatedMinecraft) "net.fabricmc.fabric-loom" else "fabric-loom"

        project.pluginManager.apply(loomPluginId)

        identity.applyCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, identity.archiveName)

        context.sharedRepositories()
        StonecutterSourceLayout.configureLoader(project, "fabric", minecraftVersion, commonProject)

        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        JavaProjectWiring.addBaseDependencies(project, context, catalog)
        project.dependencies.add("minecraft", context.library(catalog, "minecraft"))

        if (!useUnobfuscatedMinecraft) {
            FabricLoomAdapter.addMappings(project, context, catalog, hasParchment)
            FabricLoomAdapter.addFabricLoader(project, "modImplementation", context, catalog, minecraftVersion)
        } else {
            FabricLoomAdapter.addFabricLoader(project, "implementation", context, catalog, minecraftVersion)
        }

        val fabricApiConfiguration = if (useUnobfuscatedMinecraft) "implementation" else "modImplementation"
        if (useLegacyFabricApiModules) {
            FabricLoomAdapter.addLegacyFabricApiModules(project, fabricApiConfiguration)
        } else {
            project.dependencies.add(fabricApiConfiguration, context.library(catalog, "fabric-api"))
        }
        if (hasModMenu && !useUnobfuscatedMinecraft) {
            project.dependencies.add("modImplementation", context.library(catalog, "modmenu"))
        }

        if (useTeaKit && hasTeaKit) {
            val configuration = VersionPolicy.fabricTeaKitRuntimeStrategy(minecraftVersion).dependencyConfiguration
            if (configuration != null) {
                project.dependencies.add(configuration, teaKitLibrary.get())
            }
        }

        configureFabricDatagen(project, extension)
        FabricLoomAdapter.configureLoom(project, identity.modId, accessWidener)

        JavaProjectWiring.configureResourceExpansion(
            project,
            context.expandProperties(minecraftVersion, identity.loader!!, catalog),
            tomlPatterns = emptyList(),
            jsonPatterns = listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json"),
        )
        JavaProjectWiring.configureJarManifest(project, identity)
        MavenPublicationWiring.configureJavaComponentPublication(project, context)
    }

    private fun configureFabricDatagen(project: Project, extension: MultiloaderFabricExtension) {
        extension.commonDatagen.finalizeValueOnRead()
        project.afterEvaluate {
            if (!extension.commonDatagen.get()) return@afterEvaluate
            FabricLoomAdapter.configureCommonDatagen(project)
        }
    }

    private fun isStonecutterFabricProject(project: Project): Boolean =
        project.parent?.name == "fabric"
}
