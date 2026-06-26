package com.iamkaf.multiloader.publishing

import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.StonecutterConventionSupport
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.util.Locale

internal object PublicationPlanner {
    fun configuredPublications(
        project: Project,
        extension: MultiloaderPublishingExtension,
    ): List<PublicationConfig> {
        val explicit = extension.getPublications().map { publication ->
            PublicationConfig(
                name = publication.getName(),
                enabled = publication.getEnabled().get(),
                projectPath = requiredPublicationPath(publication),
                artifactTask = publication.getArtifactTask().get(),
                fallbackArtifactTask = publication.getFallbackArtifactTask().orNull,
                buildTasks = publication.getBuildTasks().getOrElse(emptyList()),
                loaders = publication.getLoaders().getOrElse(emptyList()),
                gameVersions = publication.getGameVersions().getOrElse(emptyList()),
                javaVersions = publication.getJavaVersions().getOrElse(emptyList()),
                displayName = publication.getDisplayName().orNull,
            )
        }
        if (explicit.isNotEmpty()) return explicit

        val minecraftVersion = projectProperty(project, "project.minecraft")
        val fabricArtifact = minecraftVersion?.let(VersionPolicy::fabricPublicationArtifact)
        val enabledLoaders = configuredLoaders(project)

        return listOf(
            PublicationConfig(
                "fabric",
                enabledLoaders.contains("fabric") && extension.getLoaders().getFabric().enabled.get(),
                ":fabric",
                fabricArtifact?.artifactTask ?: "remapJar",
                fabricArtifact?.fallbackArtifactTask ?: "jar",
                fabricArtifact?.buildTasks ?: emptyList(),
                listOf("fabric"),
                emptyList(),
                emptyList(),
                null,
            ),
            PublicationConfig(
                "forge",
                enabledLoaders.contains("forge") && extension.getLoaders().getForge().enabled.get(),
                ":forge",
                VersionPolicy.forgePublicationArtifact().artifactTask,
                VersionPolicy.forgePublicationArtifact().fallbackArtifactTask,
                VersionPolicy.forgePublicationArtifact().buildTasks,
                listOf("forge"),
                emptyList(),
                emptyList(),
                null,
            ),
            PublicationConfig(
                "neoforge",
                enabledLoaders.contains("neoforge") && extension.getLoaders().getNeoforge().enabled.get(),
                ":neoforge",
                VersionPolicy.neoForgePublicationArtifact().artifactTask,
                VersionPolicy.neoForgePublicationArtifact().fallbackArtifactTask,
                VersionPolicy.neoForgePublicationArtifact().buildTasks,
                listOf("neoforge"),
                emptyList(),
                emptyList(),
                null,
            ),
        )
    }

    fun plan(project: Project, publicationConfig: PublicationConfig): PublicationSpec {
        val targetProject = project.findProject(publicationConfig.projectPath)
            ?: throw IllegalStateException(
                "[Publishing] Unknown publication project path '${publicationConfig.projectPath}' for '${publicationConfig.name}'",
            )

        val jarOutput = findJarOutput(targetProject, publicationConfig.artifactTask, publicationConfig.fallbackArtifactTask)
            ?: throw IllegalStateException(
                "[Publishing] Could not locate archive task for ${targetProject.path} " +
                    "(preferred=${publicationConfig.artifactTask}, fallback=${publicationConfig.fallbackArtifactTask})",
            )

        val loaders = publicationConfig.loaders.ifEmpty { inferLoaders(targetProject) }
        MultiloaderPublishRules.requireNonEmpty("${publicationConfig.name}.loaders", loaders)

        val gameVersions = publicationConfig.gameVersions.ifEmpty { inferGameVersions(targetProject) }
        MultiloaderPublishRules.requireNonEmpty("${publicationConfig.name}.gameVersions", gameVersions)

        val javaVersions = publicationConfig.javaVersions.ifEmpty { listOf(requiredProperty(targetProject, "project.java")) }
        MultiloaderPublishRules.requireNonEmpty("${publicationConfig.name}.javaVersions", javaVersions)

        return PublicationSpec(
            name = publicationConfig.name,
            taskSuffix = taskSuffix(publicationConfig.name),
            project = targetProject,
            jarOutput = jarOutput,
            loaders = loaders,
            gameVersions = gameVersions,
            javaVersions = javaVersions,
            displayName = publicationConfig.displayName,
        )
    }

    fun stagedArtifactFile(project: Project, publication: PublicationSpec): File {
        val source = publication.archiveFile.get().asFile
        return project.layout.buildDirectory.file("publishing/artifacts/${source.name}").get().asFile
    }

    fun taskSuffix(name: String): String =
        name
            .replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .joinToString("") { token ->
                token.substring(0, 1).uppercase(Locale.ROOT) + token.substring(1)
            }

    fun requiredProperty(project: Project, name: String): String {
        val value = optionalProperty(project, name)
        if (value.isNullOrBlank()) {
            throw IllegalStateException("[Publishing] Missing required Gradle property '$name' for ${project.path}")
        }
        return value
    }

    fun optionalProperty(project: Project, name: String): String? =
        StonecutterConventionSupport.optionalProp(project, name)

    fun projectProperty(project: Project, name: String): String? = optionalProperty(project, name)

    fun booleanProperty(project: Project, name: String, defaultValue: Boolean): Boolean =
        optionalProperty(project, name)?.toBooleanStrictOrNull() ?: defaultValue

    fun requiredCsv(project: Project, name: String): List<String> =
        optionalProperty(project, name)?.let(::csv).orEmpty()

    fun csv(value: String): List<String> =
        value.split(",").map(String::trim).filter(String::isNotEmpty)

    private fun configuredLoaders(project: Project): List<String> {
        val configured = requiredCsv(project, "project.enabled-loaders")
        return configured.ifEmpty { listOf("fabric", "forge", "neoforge") }
    }

    private fun requiredPublicationPath(publication: MultiloaderPublishingExtension.Publication): String {
        if (!publication.getProjectPath().isPresent || publication.getProjectPath().get().trim().isEmpty()) {
            throw IllegalStateException("[Publishing] Publication '${publication.getName()}' must set projectPath")
        }
        return publication.getProjectPath().get().trim()
    }

    private fun findJarOutput(
        project: Project,
        preferredTaskName: String,
        fallbackTaskName: String?,
    ): JarOutput? {
        project.tasks.findByName(preferredTaskName)?.let { return asJarOutput(project, it) }

        if (fallbackTaskName != null) {
            project.tasks.findByName(fallbackTaskName)?.let { return asJarOutput(project, it) }
        }

        return null
    }

    private fun asJarOutput(project: Project, task: Task): JarOutput {
        val hasArchiveFile = (GroovyGradleDsl.invoke(task, "hasProperty", "archiveFile") as? Boolean) == true
        if (!hasArchiveFile) {
            throw IllegalStateException("[Publishing] Task ${project.path}:${task.name} does not expose archiveFile")
        }

        @Suppress("UNCHECKED_CAST")
        return JarOutput(task, GroovyGradleDsl.invoke(task, "property", "archiveFile") as Provider<RegularFile>)
    }

    private fun inferLoaders(project: Project): List<String> {
        optionalProperty(project, "loader")?.let { return listOf(it) }
        val name = project.name.lowercase(Locale.ROOT)
        return if (name in listOf("fabric", "forge", "neoforge")) listOf(name) else emptyList()
    }

    private fun inferGameVersions(project: Project): List<String> {
        val configured = optionalProperty(project, "publish.game-versions")
        if (configured != null) {
            return csv(if (configured == "auto") requiredProperty(project, "project.minecraft") else configured)
        }
        return listOf(requiredProperty(project, "project.minecraft"))
    }
}

internal data class PublicationConfig(
    val name: String,
    val enabled: Boolean,
    val projectPath: String,
    val artifactTask: String,
    val fallbackArtifactTask: String?,
    val buildTasks: List<String>,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val javaVersions: List<String>,
    val displayName: String?,
)

internal data class PublicationSpec(
    val name: String,
    val taskSuffix: String,
    val project: Project,
    val jarOutput: JarOutput,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val javaVersions: List<String>,
    val displayName: String?,
) {
    val archiveFile: Provider<RegularFile>
        get() = jarOutput.archiveFile
}

internal data class JarOutput(val task: Task, val archiveFile: Provider<RegularFile>)
