package com.iamkaf.multiloader.forge

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
import com.iamkaf.multiloader.support.ProjectIdentity
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import com.iamkaf.multiloader.support.adapters.ForgeGradleAdapter
import com.iamkaf.multiloader.support.adapters.LegacyForgeAdapter
import com.iamkaf.multiloader.support.adapters.LegacyForgeRuntimeAdapter
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.jvm.tasks.Jar
import java.io.File

class MultiloaderForgePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        ConsumerDslPolicy.requireKotlinDsl(project)
        ClientRunEnvironmentPolicy.configureForgeLikeClientRuns(project)

        if (!isStonecutterForgeProject(project)) {
            applyFlatForgePlugin(project)
            return
        }

        applyStonecutterForgePlugin(project)
    }

    private fun applyFlatForgePlugin(project: Project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin::class.java)

        val minecraftVersion = ConventionSupport.versionAlias(project, "minecraft")
        val useLegacyForgePlugin = VersionPolicy.usesLegacyForgePlugin(minecraftVersion)
        project.pluginManager.apply(if (useLegacyForgePlugin) "net.neoforged.moddev.legacyforge" else "net.minecraftforge.gradle")
        requireSupportedForgeVersion(project, minecraftVersion)

        val usesUnobfuscatedMinecraft = ConventionSupport.isUnobfuscatedMinecraft(project)
        val mixinConfigs = ConventionSupport.collectMixinConfigs(project, "forge")
        val modId = ConventionSupport.requiredProperty(project, "mod.id")

        project.tasks.named("jar", Jar::class.java) {
            if (mixinConfigs.isNotEmpty()) {
                manifest.attributes(mapOf("MixinConfigs" to mixinConfigs.joinToString(",")))
            }
        }

        val accessTransformerFile = project.file("src/main/resources/META-INF/accesstransformer.cfg")
        if (useLegacyForgePlugin) {
            LegacyForgeAdapter.configure(
                project = project,
                minecraftVersion = minecraftVersion,
                forgeVersion = ConventionSupport.versionAlias(project, "forge"),
                mixinConfigs = mixinConfigs,
                modId = modId,
                accessTransformerFile = accessTransformerFile,
                usesUnobfuscatedMinecraft = usesUnobfuscatedMinecraft,
            )
            addStrictJopt(project)
        } else {
            val forgeVersion = ConventionSupport.versionAlias(project, "forge")
            val forgeArtifactVersion = ForgeGradleAdapter.artifactVersion(minecraftVersion, forgeVersion)
            ForgeGradleAdapter.configure(
                project,
                minecraftVersion,
                mixinConfigs,
                usesUnobfuscatedMinecraft,
                accessTransformerFile,
                modId,
                forgeArtifactVersion,
            )
            val forgeCoordinate = if (usesUnobfuscatedMinecraft) {
                "net.minecraftforge:forge:$forgeVersion"
            } else {
                "net.minecraftforge:forge:$forgeArtifactVersion"
            }
            project.dependencies.add("implementation", ForgeGradleAdapter.dependency(project, forgeCoordinate))
            addStrictJopt(project)
        }

        ForgeGradleAdapter.configureOutputDirectories(project)
    }

    private fun applyStonecutterForgePlugin(project: Project) {
        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val context = MultiloaderProjectContext.of(project)
        val minecraftVersion = context.requiredProperty("project.minecraft")
        if (isGraphOnlyBuild(project)) {
            configureStonecutterForgeGraphOnly(project, context)
            return
        }

        val useLegacyForgePlugin = VersionPolicy.usesLegacyForgePlugin(minecraftVersion)
        project.pluginManager.apply(if (useLegacyForgePlugin) "net.neoforged.moddev.legacyforge" else "net.minecraftforge.gradle")
        requireSupportedForgeVersion(project, minecraftVersion)

        val catalog = context.catalogFor(minecraftVersion)
        val useUnobfuscatedMinecraft = context.useUnobfuscatedMinecraft(minecraftVersion)
        val identity = ProjectIdentity.from(context, MultiloaderProjectRole.FORGE)
        val teaKitRuntime = VersionPolicy.forgeTeaKitRuntimeStrategy(minecraftVersion, useLegacyForgePlugin)
        val accessTransformerFile = project.rootProject.file("common/src/main/resources/META-INF/accesstransformer.cfg")
        val mixinConfigs = context.mixinConfigs(identity.loader!!)
        val commonProject = project.project(":common:$minecraftVersion")

        identity.applyCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, identity.archiveName)

        context.sharedRepositories()
        StonecutterSourceLayout.configureLoader(project, "forge", minecraftVersion, commonProject)

        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        if (useLegacyForgePlugin) {
            configureStonecutterLegacyForge(
                project = project,
                context = context,
                minecraftVersion = minecraftVersion,
                mixinConfigs = mixinConfigs,
                modId = identity.modId,
                accessTransformerFile = accessTransformerFile,
            )
        } else {
            val forgeVersion = context.versionOrNull(catalog, "forge")
                ?: throw GradleException("Missing Forge version for ${project.path}")
            val forgeArtifactVersion = ForgeGradleAdapter.artifactVersion(minecraftVersion, forgeVersion)
            ForgeGradleAdapter.configure(
                project,
                minecraftVersion,
                mixinConfigs,
                useUnobfuscatedMinecraft,
                accessTransformerFile,
                identity.modId,
                forgeArtifactVersion,
            )
        }

        JavaProjectWiring.addBaseDependencies(project, context, catalog)
        LoaderDependencyPolicy.addForgeLoaderLibraries(project, context, catalog, identity, minecraftVersion)

        if (!useLegacyForgePlugin) {
            val forgeVersion = context.versionOrNull(catalog, "forge")
                ?: throw GradleException("Missing Forge version for ${project.path}")
            val forgeCoordinate = if (useUnobfuscatedMinecraft) {
                "net.minecraftforge:forge:$forgeVersion"
            } else {
                "net.minecraftforge:forge:${ForgeGradleAdapter.artifactVersion(minecraftVersion, forgeVersion)}"
            }
            project.dependencies.add("implementation", ForgeGradleAdapter.dependency(project, forgeCoordinate))
        }

        addStrictJopt(project)

        LoaderDependencyPolicy.addTeaKitRuntime(
            project = project,
            context = context,
            catalog = catalog,
            identity = identity,
            loader = LoaderId.FORGE,
            minecraftVersion = minecraftVersion,
            strategy = teaKitRuntime,
        )

        ForgeGradleAdapter.configureOutputDirectories(project)
        JavaProjectWiring.configureResourceExpansion(
            project,
            context.expandProperties(minecraftVersion, identity.loader!!, catalog),
            tomlPatterns = listOf("META-INF/mods.toml"),
            jsonPatterns = listOf("pack.mcmeta", "*.mixins.json"),
        )
        JavaProjectWiring.configureJarManifest(project, identity, mixinConfigs = mixinConfigs)
        MavenPublicationWiring.configureJavaComponentPublication(project, context)
    }

    private fun configureStonecutterForgeGraphOnly(
        project: Project,
        context: MultiloaderProjectContext,
    ) {
        val identity = ProjectIdentity.from(context, MultiloaderProjectRole.FORGE)
        identity.applyCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, identity.archiveName)

        StonecutterSourceLayout.configureGraphOnly(project)
        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        project.tasks.withType(Jar::class.java).configureEach {
            exclude(".cache/**")
        }

        project.tasks.register("runClient") {
            group = "minecraft"
            description = "Placeholder Forge client run task for graph-only ${identity.minecraftVersion} builds."
        }

        MavenPublicationWiring.configureJavaComponentPublication(project, context)
    }

    private fun configureStonecutterLegacyForge(
        project: Project,
        context: MultiloaderProjectContext,
        minecraftVersion: String,
        mixinConfigs: List<String>,
        modId: String,
        accessTransformerFile: File,
    ) {
        val catalog = context.catalogFor(minecraftVersion)
        LegacyForgeAdapter.configure(
            project = project,
            minecraftVersion = minecraftVersion,
            forgeVersion = context.versionOrNull(catalog, "forge")
                ?: throw GradleException("Missing Forge version for ${project.path}"),
            mixinConfigs = mixinConfigs,
            modId = modId,
            accessTransformerFile = accessTransformerFile,
            usesUnobfuscatedMinecraft = false,
        )

        LegacyForgeRuntimeAdapter.configure(project, context, catalog, ProjectIdentity.from(context, MultiloaderProjectRole.FORGE), minecraftVersion)
    }

    private fun addStrictJopt(project: Project) {
        val dependency = project.dependencies.create("net.sf.jopt-simple:jopt-simple:5.0.4") as ExternalModuleDependency
        dependency.version { strictly("5.0.4") }
        project.dependencies.add("implementation", dependency)
    }

    private fun requireSupportedForgeVersion(project: Project, minecraftVersion: String?) {
        if (minecraftVersion != null && !VersionPolicy.isForgeConventionSupported(minecraftVersion)) {
            throw GradleException(
                "Forge convention support starts at Minecraft 1.17. Keep ${project.path} on a repo-local legacy setup for $minecraftVersion.",
            )
        }
    }

    private fun isGraphOnlyBuild(project: Project): Boolean {
        val taskNames = project.gradle.startParameter.taskNames
        return taskNames.isNotEmpty() && taskNames.all { taskName ->
            val requested = taskName.split(":").last()
            requested == "writeMultiloaderGraph" || requested == "printMultiloaderGraph"
        }
    }

    private fun isStonecutterForgeProject(project: Project): Boolean =
        project.parent?.name == "forge"
}
