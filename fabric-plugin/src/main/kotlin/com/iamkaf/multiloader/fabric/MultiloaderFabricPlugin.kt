package com.iamkaf.multiloader.fabric

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.ConsumerDslPolicy
import com.iamkaf.multiloader.support.DatagenOutputPlanner
import com.iamkaf.multiloader.support.FabricCompatibilityPolicy
import com.iamkaf.multiloader.support.JavaProjectWiring
import com.iamkaf.multiloader.support.LoaderDependencyPolicy
import com.iamkaf.multiloader.support.LoaderId
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
        ConsumerDslPolicy.requireKotlinDsl(project)

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
            val minecraftVersion = ConventionSupport.requiredProperty(project, "project.minecraft")
            FabricCompatibilityPolicy.configureDatagenRuntimeAvailability(project, minecraftVersion)
            ConventionSupport.configureFabricBaseDependencies(project)
            configureFabricDatagen(
                project,
                extension,
                minecraftVersion,
                DatagenOutputPlanner.commonDatagenOutputDirectory(
                    project = project,
                    minecraftVersion = minecraftVersion,
                    usesStonecutter = false,
                ),
            )

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
        val hasParchment = context.versionOrNull(catalog, "parchment") != null
        val modMenuVersion = context.versionOrNull(catalog, "modmenu")
        val hasModMenu = modMenuVersion != null && modMenuVersion != "null"
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
        FabricCompatibilityPolicy.configureDatagenRuntimeAvailability(project, minecraftVersion)
        FabricCompatibilityPolicy.excludeDatagenSourcesFromGameplayRuns(project, minecraftVersion)
        LoaderDependencyPolicy.configureFabricResolutionCompatibility(project, minecraftVersion)

        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        JavaProjectWiring.addBaseDependencies(project, context, catalog)
        LoaderDependencyPolicy.addFabricLoaderLibraries(project, context, catalog, identity, minecraftVersion)
        LoaderDependencyPolicy.addFabricDatagenApi(project, context, catalog, minecraftVersion)
        project.dependencies.add("minecraft", context.library(catalog, "minecraft"))

        if (!useUnobfuscatedMinecraft) {
            FabricLoomAdapter.addMappings(project, context, catalog, hasParchment)
            FabricLoomAdapter.addFabricLoader(project, "modImplementation", context, catalog, minecraftVersion)
        } else {
            FabricLoomAdapter.addFabricLoader(project, "implementation", context, catalog, minecraftVersion)
        }

        LoaderDependencyPolicy.addFabricApi(project, context, catalog, minecraftVersion, useUnobfuscatedMinecraft)
        if (hasModMenu) {
            val modMenuConfiguration = if (useUnobfuscatedMinecraft) "implementation" else "modImplementation"
            project.dependencies.add(modMenuConfiguration, context.library(catalog, "modmenu"))
        }

        LoaderDependencyPolicy.addTeaKitRuntime(
            project = project,
            context = context,
            catalog = catalog,
            identity = identity,
            loader = LoaderId.FABRIC,
            minecraftVersion = minecraftVersion,
            strategy = VersionPolicy.fabricTeaKitRuntimeStrategy(minecraftVersion),
        )

        configureFabricDatagen(
            project,
            extension,
            minecraftVersion,
            DatagenOutputPlanner.commonDatagenOutputDirectory(
                project = project,
                minecraftVersion = minecraftVersion,
                usesStonecutter = true,
            ),
        )
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

    private fun configureFabricDatagen(
        project: Project,
        extension: MultiloaderFabricExtension,
        minecraftVersion: String,
        outputDirectory: java.io.File,
    ) {
        extension.commonDatagen.finalizeValueOnRead()
        project.afterEvaluate {
            if (!extension.commonDatagen.get()) return@afterEvaluate
            if (!FabricCompatibilityPolicy.supportsFabricApiDatagenRuntime(minecraftVersion)) return@afterEvaluate
            FabricLoomAdapter.configureCommonDatagen(project, outputDirectory)
        }
    }

    private fun isStonecutterFabricProject(project: Project): Boolean =
        project.parent?.name == "fabric"
}
