package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.publishing.MultiloaderPublishingExtension
import com.iamkaf.multiloader.support.MultiloaderTargetScope
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Project
import java.io.File

object RootPublicationDefaults {
    fun configure(
        project: Project,
        extension: MultiloaderPublishingExtension,
        versionDirs: List<File>,
        targetScope: MultiloaderTargetScope,
    ) {
        versionDirs.filter { targetScope.includesVersion(it.name) }.forEach { dir ->
            val props = RootVersionMatrix.versionMetadata(dir)
            val minecraftVersion = props.getProperty("project.minecraft")
            val javaVersion = props.getProperty("project.java")
            val enabledLoaders = targetScope.loadersFor(dir.name)

            enabledLoaders.forEach { loaderId ->
                val publication = extension.getPublications().maybeCreate("$minecraftVersion-$loaderId")
                publication.getProjectPath().set(":$loaderId:$minecraftVersion")
                val artifact = when (loaderId) {
                    "fabric" -> VersionPolicy.fabricPublicationArtifact(minecraftVersion)
                    "forge" -> VersionPolicy.forgePublicationArtifact()
                    else -> VersionPolicy.neoForgePublicationArtifact()
                }
                publication.getArtifactTask().set(artifact.artifactTask)
                artifact.fallbackArtifactTask?.let { publication.getFallbackArtifactTask().set(it) }
                publication.getBuildTasks().addAll(artifact.buildTasks)
                publication.getLoaders().add(loaderId)
                publication.getGameVersions().add(minecraftVersion)
                publication.getJavaVersions().add(javaVersion)
            }
        }
    }
}
