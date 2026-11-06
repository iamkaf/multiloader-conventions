package com.iamkaf.multiloader.publishing

import org.gradle.api.Project
import java.io.File
import java.util.Locale
import java.util.Properties

/** Turns the planned publications into the manifest, using the same rules the upload tasks use. */
internal object ReleaseManifestPlanner {
    fun plan(
        project: Project,
        extension: MultiloaderPublishingExtension,
        specs: List<PublicationSpec>,
    ): ReleaseManifest =
        ReleaseManifest(
            mod = PublicationPlanner.requiredProperty(project, "mod.id"),
            commit = commit(project),
            modrinthId = extension.getPublish().getModrinth().id.orNull,
            curseforgeId = extension.getPublish().getCurseforge().id.orNull,
            releaseType = extension.getConfig().releaseType.get(),
            files = specs.map { fileFor(project, extension, it) },
        )

    private fun fileFor(
        project: Project,
        extension: MultiloaderPublishingExtension,
        spec: PublicationSpec,
    ): ManifestFile {
        val staged = PublicationPlanner.stagedArtifactFile(project, spec)
        if (!staged.isFile) {
            throw IllegalStateException("[Publishing] Expected staged jar does not exist: $staged")
        }
        val minecraft = spec.gameVersions.first()
        val lineProperty = { name: String -> versionProperty(project, spec, minecraft, name) }

        return ManifestFile(
            path = staged.name,
            sha256 = ReleaseManifestWriter.sha256(staged),
            size = staged.length(),
            minecraft = minecraft,
            version = spec.version,
            displayName = PublishingDisplay.publicationDisplayName(staged, spec),
            loaders = MultiloaderPublishRules.modrinthNormalizeLoaders(spec.loaders),
            horizontal = spec.name.endsWith(MERGED_SUFFIX),
            modrinthGameVersions = MultiloaderPublishRules.modrinthNormalizeGameVersions(spec.gameVersions),
            curseforgeGameVersions = MultiloaderPublishRules.curseNormalizeGameVersions(spec.gameVersions),
            // A merged jar can span Java levels; the highest one is the floor it needs.
            java = spec.javaVersions.maxOf { it.trim().removePrefix("Java ").toInt() },
            environment = extension.getPublish().getCurseforge().environment.get().lowercase(Locale.ROOT),
            modrinthDependencies = dependencies(
                extension.getPublish().getModrinth().getDependencies(),
                lineProperty("dependencies.modrinth.required"),
                MODRINTH_TYPES,
            ),
            curseforgeDependencies = dependencies(
                extension.getPublish().getCurseforge().getDependencies(),
                lineProperty("dependencies.curseforge.required"),
                CURSEFORGE_TYPES,
            ),
        )
    }

    /**
     * Required dependencies come from the file's own Minecraft line, because they differ per line.
     * The upload tasks read the same property for the one line they are run for.
     */
    private fun dependencies(
        configured: MultiloaderPublishingExtension.Publish.Dependencies,
        requiredForLine: String?,
        types: DependencyTypes,
    ): List<ManifestDependency> {
        val required = requiredForLine?.let(PublicationPlanner::csv)
            ?: configured.getRequired().getOrElse(emptyList())
        return required.map { ManifestDependency(it, types.required) } +
            configured.getOptional().getOrElse(emptyList()).map { ManifestDependency(it, types.optional) } +
            configured.getIncompatible().getOrElse(emptyList()).map { ManifestDependency(it, types.incompatible) } +
            configured.getEmbedded().getOrElse(emptyList()).map { ManifestDependency(it, types.embedded) }
    }

    private fun versionProperty(project: Project, spec: PublicationSpec, minecraft: String, name: String): String? {
        val file = File(project.rootProject.projectDir, "versions/$minecraft/gradle.properties")
        if (file.isFile) {
            val properties = Properties().apply { file.inputStream().use(::load) }
            properties.getProperty(name)?.takeIf(String::isNotBlank)?.let { return it }
        }
        return PublicationPlanner.optionalProperty(spec.project, name)
    }

    private fun commit(project: Project): String {
        PublicationPlanner.optionalProperty(project, "publish.commit")?.let { return it }
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(project.rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0 || !Regex("[0-9a-f]{40}").matches(output)) {
            throw IllegalStateException("[Publishing] Could not read the HEAD commit: $output")
        }
        return output
    }

    private class DependencyTypes(
        val required: String,
        val optional: String,
        val incompatible: String,
        val embedded: String,
    )

    private val MODRINTH_TYPES = DependencyTypes("required", "optional", "incompatible", "embedded")
    private val CURSEFORGE_TYPES =
        DependencyTypes("requiredDependency", "optionalDependency", "incompatible", "embeddedLibrary")
    private const val MERGED_SUFFIX = "-multiloader"
}
