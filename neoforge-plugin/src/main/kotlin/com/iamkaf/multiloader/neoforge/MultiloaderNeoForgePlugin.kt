package com.iamkaf.multiloader.neoforge

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ClientRunEnvironmentPolicy
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.ConsumerDslPolicy
import com.iamkaf.multiloader.support.JavaProjectWiring
import com.iamkaf.multiloader.support.LoaderDependencyPolicy
import com.iamkaf.multiloader.support.LoaderId
import com.iamkaf.multiloader.support.MavenPublicationWiring
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.MultiloaderProjectRole
import com.iamkaf.multiloader.support.NeoForgeToolchainStrategy
import com.iamkaf.multiloader.support.ProjectIdentity
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import com.iamkaf.multiloader.support.adapters.NeoForgeModDevAdapter
import com.iamkaf.multiloader.support.adapters.NeoGradleUserdevAdapter
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderNeoForgePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        ConsumerDslPolicy.requireKotlinDsl(project)
        ClientRunEnvironmentPolicy.configureForgeLikeClientRuns(project)

        if (!isStonecutterNeoForgeProject(project)) {
            applyFlatNeoForgePlugin(project)
            return
        }

        applyStonecutterNeoForgePlugin(project)
    }

    private fun applyFlatNeoForgePlugin(project: Project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin::class.java)
        project.pluginManager.apply("net.neoforged.moddev")

        NeoForgeModDevAdapter.configure(
            project = project,
            neoforgeVersion = ConventionSupport.versionAlias(project, "neoforge"),
            modId = ConventionSupport.requiredProperty(project, "mod.id"),
            accessTransformerFile = ConventionSupport.commonFile(project, "src/main/resources/META-INF/accesstransformer.cfg"),
            parchmentVersion = ConventionSupport.optionalVersionAlias(project, "parchment"),
            parchmentMinecraftVersion = ConventionSupport.optionalVersionAlias(project, "parchment-minecraft")
                ?: ConventionSupport.versionAlias(project, "minecraft"),
        )
    }

    private fun applyStonecutterNeoForgePlugin(project: Project) {
        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val context = MultiloaderProjectContext.of(project)
        val minecraftVersion = context.requiredProperty("project.minecraft")
        val useNeoGradleUserdev =
            VersionPolicy.neoForgeToolchainStrategy(minecraftVersion) == NeoForgeToolchainStrategy.NEOGRADLE_USERDEV
        project.pluginManager.apply(if (useNeoGradleUserdev) "net.neoforged.gradle.userdev" else "net.neoforged.moddev")

        val catalog = context.catalogFor(minecraftVersion)
        val identity = ProjectIdentity.from(context, MultiloaderProjectRole.NEOFORGE)
        val accessTransformerFile = project.rootProject.file("common/src/main/resources/META-INF/accesstransformer.cfg")
        val commonProject = project.project(":common:$minecraftVersion")

        identity.applyCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, identity.archiveName)

        context.sharedRepositories()
        StonecutterSourceLayout.configureLoader(project, "neoforge", minecraftVersion, commonProject)

        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        JavaProjectWiring.addBaseDependencies(project, context, catalog)
        LoaderDependencyPolicy.addNeoForgeLoaderLibraries(project, context, catalog, identity)
        LoaderDependencyPolicy.addC2meRuntime(
            project = project,
            context = context,
            catalog = catalog,
            identity = identity,
            loader = LoaderId.NEOFORGE,
            minecraftVersion = minecraftVersion,
        )
        LoaderDependencyPolicy.addTeaKitRuntime(
            project = project,
            context = context,
            catalog = catalog,
            identity = identity,
            loader = LoaderId.NEOFORGE,
            minecraftVersion = minecraftVersion,
            strategy = com.iamkaf.multiloader.support.TeaKitRuntimeStrategy.RUNTIME_ONLY,
        )

        if (useNeoGradleUserdev) {
            NeoGradleUserdevAdapter.configure(project, context.versionOrNull(catalog, "neoforge"), identity.modId)
        } else {
            NeoForgeModDevAdapter.configure(
                project = project,
                neoforgeVersion = context.versionOrNull(catalog, "neoforge"),
                modId = identity.modId,
                accessTransformerFile = accessTransformerFile,
                parchmentVersion = context.versionOrNull(catalog, "parchment"),
                parchmentMinecraftVersion = context.versionOrNull(catalog, "parchment-minecraft") ?: minecraftVersion,
            )
        }

        JavaProjectWiring.configureResourceExpansion(
            project,
            context.expandProperties(minecraftVersion, identity.loader!!, catalog),
            tomlPatterns = listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml"),
            jsonPatterns = listOf("pack.mcmeta", "*.mixins.json"),
        )
        JavaProjectWiring.configureJarManifest(project, identity)
        MavenPublicationWiring.configureJavaComponentPublication(project, context)
    }

    private fun isStonecutterNeoForgeProject(project: Project): Boolean =
        project.parent?.name == "neoforge"
}
