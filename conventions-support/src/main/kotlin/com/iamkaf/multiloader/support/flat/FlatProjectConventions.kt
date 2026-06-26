package com.iamkaf.multiloader.support.flat

import com.iamkaf.multiloader.support.JavaProjectWiring
import com.iamkaf.multiloader.support.MavenPublicationWiring
import com.iamkaf.multiloader.support.MetadataExpansion
import com.iamkaf.multiloader.support.RepositoryPolicy
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer

object FlatProjectConventions {
    val loaderProjects: List<String> = listOf("fabric", "forge", "neoforge")

    fun configureCommonProject(project: Project) {
        project.pluginManager.apply(JavaLibraryPlugin::class.java)
        project.pluginManager.apply("maven-publish")

        RepositoryPolicy.configureProjectRepositories(project)
        configureCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, "${FlatProjectAccess.requiredProperty(project, "mod.id")}-${project.name}")
        JavaProjectWiring.configureJavaBuild(
            project,
            FlatProjectAccess.requiredProperty(project, "project.java").toInt(),
            includeGeneratedResources = true,
        )
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)
        JavaProjectWiring.configureResourceExpansion(
            project,
            MetadataExpansion.flat(project),
            tomlPatterns = listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml"),
            jsonPatterns = listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json"),
        )
        JavaProjectWiring.configureJarManifest(
            project,
            com.iamkaf.multiloader.support.ProjectIdentity(
                group = project.group.toString(),
                version = project.version.toString(),
                modId = FlatProjectAccess.requiredProperty(project, "mod.id"),
                modName = FlatProjectAccess.requiredProperty(project, "mod.name"),
                authors = FlatProjectAccess.optionalProperty(project, "mod.authors"),
                minecraftVersion = FlatProjectAccess.versionAlias(project, "minecraft"),
                javaVersion = FlatProjectAccess.requiredProperty(project, "project.java").toInt(),
                role = com.iamkaf.multiloader.support.MultiloaderProjectRole.COMMON,
            ),
            implementationTitle = project.name,
        )
        JavaProjectWiring.configureLicensePackaging(
            project,
            FlatProjectAccess.resolveLicenseFile(project),
            FlatProjectAccess.requiredProperty(project, "mod.name"),
        )
        configureCapabilities(project)
        MavenPublicationWiring.configureFlatJavaPublication(project)
        configureCommonArtifacts(project)
    }

    fun configureCoordinates(project: Project) {
        project.rootProject.findProperty("project.group")?.let { project.group = it.toString() }
        project.rootProject.findProperty("project.version")?.let { project.version = it.toString() }
    }

    fun configureCapabilities(project: Project) {
        listOf("apiElements", "runtimeElements", "sourcesElements", "javadocElements").forEach { variant ->
            project.configurations.named(variant) {
                val archivesName = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
                outgoing.capability("${project.group}:${project.name}:${project.version}")
                outgoing.capability("${project.group}:$archivesName:${project.version}")
                outgoing.capability(
                    "${project.group}:${FlatProjectAccess.requiredProperty(project, "mod.id")}-${project.name}-${FlatProjectAccess.versionAlias(project, "minecraft")}:${project.version}",
                )
                outgoing.capability("${project.group}:${FlatProjectAccess.requiredProperty(project, "mod.id")}:${project.version}")
            }

            project.extensions.configure(PublishingExtension::class.java) {
                publications.withType(MavenPublication::class.java).configureEach {
                    suppressPomMetadataWarningsFor(variant)
                }
            }
        }
    }

    fun configureCommonArtifacts(project: Project) {
        if (project.name != "common") return

        val commonJava = project.configurations.maybeCreate("commonJava")
        commonJava.isCanBeResolved = false
        commonJava.isCanBeConsumed = true

        val commonResources = project.configurations.maybeCreate("commonResources")
        commonResources.isCanBeResolved = false
        commonResources.isCanBeConsumed = true

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        project.artifacts {
            add("commonJava", sourceSets.named("main").get().java.sourceDirectories.singleFile)
            sourceSets.named("main").get().resources.sourceDirectories.files.forEach { directory ->
                add("commonResources", directory)
            }
        }
    }
}
