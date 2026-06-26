package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.CommonArtifacts
import com.iamkaf.multiloader.support.CommonToolchainStrategy
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.JavaProjectWiring
import com.iamkaf.multiloader.support.MavenPublicationWiring
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.MultiloaderProjectRole
import com.iamkaf.multiloader.support.ProjectIdentity
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import com.iamkaf.multiloader.support.adapters.FabricLoomAdapter
import com.iamkaf.multiloader.support.adapters.LegacyForgeAdapter
import com.iamkaf.multiloader.support.adapters.NeoForgeModDevAdapter
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class MultiloaderCommonPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!isCommonProject(project)) {
            ConventionSupport.configureCommonProject(project)
            return
        }

        val context = MultiloaderProjectContext.of(project)

        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val minecraftVersion = context.requiredProperty("project.minecraft")
        val catalog = context.catalogFor(minecraftVersion)
        val identity = ProjectIdentity.from(context, MultiloaderProjectRole.COMMON)
        val toolchainStrategy = VersionPolicy.commonToolchainStrategy(
            minecraftVersion,
            hasNeoForm = context.versionOrNull(catalog, "neoform") != null,
        )
        val hasParchment = context.versionOrNull(catalog, "parchment") != null
        val accessTransformerFile = resolveAccessTransformerFile(project)
        val usesStonecutter = project.tasks.findByName("stonecutterGenerate") != null

        when (toolchainStrategy) {
            CommonToolchainStrategy.FABRIC_LOOM -> project.pluginManager.apply("fabric-loom")
            CommonToolchainStrategy.LEGACY_FORGE -> project.pluginManager.apply("net.neoforged.moddev.legacyforge")
            CommonToolchainStrategy.NEOFORM -> project.pluginManager.apply("net.neoforged.moddev")
        }

        identity.applyCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, identity.archiveName)

        context.sharedRepositories()
        StonecutterSourceLayout.configureCommon(project, minecraftVersion, usesStonecutter)

        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        when (toolchainStrategy) {
            CommonToolchainStrategy.FABRIC_LOOM -> {
                project.dependencies.add("minecraft", context.library(catalog, "minecraft"))
                FabricLoomAdapter.addMappings(project, context, catalog, hasParchment)
                project.dependencies.add("modImplementation", context.library(catalog, "fabric-loader"))
            }
            CommonToolchainStrategy.LEGACY_FORGE -> LegacyForgeAdapter.configureCommon(
                project = project,
                minecraftVersion = context.versionOrNull(catalog, "minecraft"),
                parchmentMinecraftVersion = context.versionOrNull(catalog, "parchment-minecraft")
                    ?: context.versionOrNull(catalog, "minecraft"),
                parchmentVersion = context.versionOrNull(catalog, "parchment"),
                accessTransformerFile = accessTransformerFile,
            )
            CommonToolchainStrategy.NEOFORM -> NeoForgeModDevAdapter.configureCommon(
                project = project,
                neoFormVersion = context.versionOrNull(catalog, "neoform"),
                parchmentMinecraftVersion = context.versionOrNull(catalog, "parchment-minecraft")
                    ?: context.versionOrNull(catalog, "minecraft"),
                parchmentVersion = context.versionOrNull(catalog, "parchment"),
                accessTransformerFile = accessTransformerFile,
            )
        }

        if (usesStonecutter) {
            StonecutterSourceLayout.attachStagingDependencies(project)
        }

        CommonArtifacts.configureConsumableCommonArtifacts(project)

        JavaProjectWiring.addBaseDependencies(project, context, catalog)

        if (toolchainStrategy == CommonToolchainStrategy.FABRIC_LOOM) {
            MavenPublicationWiring.configureFabricRemappedPublication(project, context)
        } else {
            MavenPublicationWiring.configureJavaComponentPublication(project, context)
        }

        JavaProjectWiring.configureCapabilities(project, identity, extraCapabilities = listOf(identity.modId))
    }

    private fun isCommonProject(project: Project): Boolean =
        project.name == "common" || project.parent?.name == "common"

    private fun resolveAccessTransformerFile(project: Project): File =
        if (project.parent?.name == "common") {
            project.rootProject.file("common/src/main/resources/META-INF/accesstransformer.cfg")
        } else {
            project.file("src/main/resources/META-INF/accesstransformer.cfg")
        }
}
