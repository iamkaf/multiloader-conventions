package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import me.hypherionmc.curseupload.CurseUploadApi
import me.hypherionmc.curseupload.constants.CurseChangelogType
import me.hypherionmc.curseupload.constants.CurseReleaseType
import me.hypherionmc.curseupload.requests.CurseArtifact
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.regex.Pattern

class MultiloaderPublishingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw IllegalStateException("com.iamkaf.multiloader.publishing must be applied to the root project only.")
        }

        val extension = project.extensions.create(
            EXTENSION_NAME,
            MultiloaderPublishingExtension::class.java,
            project.objects,
        )
        configureDefaults(project, extension)

        val assembleAll = project.tasks.register("publishingAssemble") {
            group = "publishing"
            description = "Aggregate all enabled artifacts for release publishing."
        }

        val publishCurseforgeAll = project.tasks.register("publishCurseforge") {
            group = "publishing"
            description = "Upload all enabled artifacts to CurseForge."
        }

        val publishModrinthAll = project.tasks.register("publishModrinth") {
            group = "publishing"
            description = "Upload all enabled artifacts to Modrinth."
        }

        val publishAll = project.tasks.register("publishMod") {
            group = "publishing"
            description = "Upload all enabled artifacts to configured platforms."
            dependsOn(publishCurseforgeAll, publishModrinthAll)
        }

        project.tasks.register("publishingPublish") {
            group = "publishing"
            description = "Compatibility aggregate for publishMod."
            dependsOn(publishAll)
        }

        project.tasks.register("publishingRelease") {
            group = "publishing"
            description = "Compatibility aggregate for publishMod."
            dependsOn(publishAll)
        }

        project.gradle.projectsEvaluated {
            PublicationPlanner.configuredPublications(project, extension).forEach { publicationConfig ->
                val taskSuffix = PublicationPlanner.taskSuffix(publicationConfig.name)

                if (!publicationConfig.enabled) {
                    val assembleTask = project.tasks.register("publishingAssemble$taskSuffix") {
                        group = "publishing"
                        description = "Aggregate the ${publicationConfig.name} artifact for release publishing."
                        onlyIf { false }
                    }
                    assembleAll.configure { dependsOn(assembleTask) }

                    val curseTask = project.tasks.register("publishCurseforge$taskSuffix") {
                        group = "publishing"
                        description = "Upload the ${publicationConfig.name} artifact to CurseForge."
                        dependsOn(assembleTask)
                        onlyIf { false }
                    }
                    publishCurseforgeAll.configure { dependsOn(curseTask) }

                    val modrinthTask = project.tasks.register("publishModrinth$taskSuffix") {
                        group = "publishing"
                        description = "Upload the ${publicationConfig.name} artifact to Modrinth."
                        dependsOn(assembleTask)
                        onlyIf { false }
                    }
                    publishModrinthAll.configure { dependsOn(modrinthTask) }
                    return@forEach
                }

                val spec = PublicationPlanner.plan(project, publicationConfig)

                val assembleTask = project.tasks.register("publishingAssemble${spec.taskSuffix}") {
                    group = "publishing"
                    description = "Aggregate the ${publicationConfig.name} artifact for release publishing."

                    publicationConfig.buildTasks.forEach { taskName ->
                        val extraTask = spec.project.tasks.findByName(taskName)
                        if (extraTask != null) {
                            dependsOn(extraTask)
                        }
                    }
                    dependsOn(spec.jarOutput.task)

                    doFirst {
                        project.logger.lifecycle(
                            "[Publishing] Assemble starting for ${publicationConfig.name} " +
                                "(dryRun=${extension.getConfig().dryRun.get()}, releaseType=${extension.getConfig().releaseType.get()}, " +
                                "loaders=${spec.loaders}, gameVersions=${spec.gameVersions})",
                        )
                    }

                    doLast {
                        val source = spec.archiveFile.get().asFile
                        if (!source.exists()) {
                            throw IllegalStateException("[Publishing] Expected jar does not exist for ${spec.project.path}: $source")
                        }

                        val dest = PublicationPlanner.stagedArtifactFile(project, spec)
                        dest.parentFile.mkdirs()
                        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)

                        if (!extension.getPublish().disableEmptyJarCheck.get()) {
                            MultiloaderPublishRules.checkEmptyJar(dest, spec.loaders)
                        }

                        project.logger.lifecycle("[Publishing] Copied ${spec.project.path} -> $dest")
                    }
                }

                assembleAll.configure { dependsOn(assembleTask) }

                val curseTask = project.tasks.register("publishCurseforge${spec.taskSuffix}") {
                    group = "publishing"
                    description = "Upload the ${publicationConfig.name} artifact to CurseForge."
                    dependsOn(assembleTask)
                    onlyIf { extension.getPublish().getCurseforge().id.isPresent }

                    doLast {
                        val isDryRun = extension.getConfig().dryRun.get()
                        if (!isDryRun && !extension.getPublish().getCurseforge().token.isPresent) {
                            throw IllegalStateException("[Publishing] CurseForge publishing requires publish.curseforge.token unless dryRun=true")
                        }

                        val curseGameVersions = MultiloaderPublishRules.curseNormalizeGameVersions(spec.gameVersions)
                        project.logger.lifecycle("[Publishing] Destinations=[curseforge]")
                        project.logger.lifecycle("[Publishing] loaders=${spec.loaders}")
                        project.logger.lifecycle("[Publishing] gameVersions=${spec.gameVersions}")

                        publishCurseForge(
                            project,
                            extension,
                            resolveChangelog(project, extension, spec),
                            isDryRun,
                            curseGameVersions,
                            PublicationPlanner.stagedArtifactFile(project, spec),
                            spec,
                        )

                        if (isDryRun) {
                            project.logger.lifecycle("[Publishing] dryRun=true -> skipping live publish")
                        }
                    }
                }

                val modrinthTask = project.tasks.register("publishModrinth${spec.taskSuffix}") {
                    group = "publishing"
                    description = "Upload the ${publicationConfig.name} artifact to Modrinth."
                    dependsOn(assembleTask)
                    onlyIf { extension.getPublish().getModrinth().id.isPresent }

                    doLast {
                        val isDryRun = extension.getConfig().dryRun.get()
                        if (!isDryRun && !extension.getPublish().getModrinth().token.isPresent) {
                            throw IllegalStateException("[Publishing] Modrinth publishing requires publish.modrinth.token unless dryRun=true")
                        }

                        val modrinthGameVersions = MultiloaderPublishRules.modrinthNormalizeGameVersions(spec.gameVersions)
                        project.logger.lifecycle("[Publishing] Destinations=[modrinth]")
                        project.logger.lifecycle("[Publishing] loaders=${spec.loaders}")
                        project.logger.lifecycle("[Publishing] gameVersions=${spec.gameVersions}")

                        publishModrinth(
                            project,
                            extension,
                            resolveChangelog(project, extension, spec),
                            isDryRun,
                            modrinthGameVersions,
                            PublicationPlanner.stagedArtifactFile(project, spec),
                            spec,
                        )

                        if (isDryRun) {
                            project.logger.lifecycle("[Publishing] dryRun=true -> skipping live publish")
                        }
                    }
                }

                publishCurseforgeAll.configure { dependsOn(curseTask) }
                publishModrinthAll.configure { dependsOn(modrinthTask) }
            }
        }
    }

    private fun configureDefaults(project: Project, extension: MultiloaderPublishingExtension) {
        extension.getConfig().dryRun.convention(PublicationPlanner.booleanProperty(project, "publish.dry-run", false))
        extension.getConfig().releaseType.convention(PublicationPlanner.optionalProperty(project, "publish.release-type") ?: "release")
        extension.getPublish().getCurseforge().environment.convention(curseEnvironment(project))

        PublicationPlanner.optionalProperty(project, "publish.modrinth.id")?.let { modrinthId ->
            extension.getPublish().getModrinth().id.convention(modrinthId)
        }

        PublicationPlanner.optionalProperty(project, "publish.curseforge.id")?.let { curseId ->
            extension.getPublish().getCurseforge().id.convention(curseId)
        }

        System.getenv("MODRINTH_TOKEN")?.takeIf(String::isNotBlank)?.let { token ->
            extension.getPublish().getModrinth().token.convention(token)
        }

        System.getenv("CURSEFORGE_TOKEN")?.takeIf(String::isNotBlank)?.let { token ->
            extension.getPublish().getCurseforge().token.convention(token)
        }

        PublicationPlanner.requiredCsv(project, "dependencies.modrinth.required").forEach { dependency ->
            extension.getPublish().getModrinth().getDependencies().required(dependency)
        }
        PublicationPlanner.requiredCsv(project, "dependencies.curseforge.required").forEach { dependency ->
            extension.getPublish().getCurseforge().getDependencies().required(dependency)
        }

        val defaultChangelog = if (project.file("../changelog.md").exists()) "../changelog.md" else "changelog.md"
        extension.getMetadata().changelogFile.convention(defaultChangelog)
    }

    private fun publishModrinth(
        project: Project,
        extension: MultiloaderPublishingExtension,
        changelog: String,
        dryRun: Boolean,
        gameVersions: List<String>,
        file: File,
        publication: PublicationSpec,
    ) {
        val token = if (dryRun) extension.getPublish().getModrinth().token.orNull ?: "" else extension.getPublish().getModrinth().token.get()
        val client = ModrinthPublishingClient(token)
        val projectId = if (dryRun) {
            extension.getPublish().getModrinth().id.get()
        } else {
            client.resolveProjectId(extension.getPublish().getModrinth().id.get())
        }

        val dependencies = mutableListOf<Map<String, String>>()
        fun addDependencies(slugs: List<String>, type: String) {
            slugs.forEach { slug ->
                val dependencyId = if (dryRun) slug else client.resolveProjectId(slug)
                dependencies.add(
                    linkedMapOf(
                        "project_id" to dependencyId,
                        "dependency_type" to type,
                    ),
                )
            }
        }
        addDependencies(extension.getPublish().getModrinth().getDependencies().getRequired().getOrElse(emptyList()), "required")
        addDependencies(extension.getPublish().getModrinth().getDependencies().getOptional().getOrElse(emptyList()), "optional")
        addDependencies(extension.getPublish().getModrinth().getDependencies().getIncompatible().getOrElse(emptyList()), "incompatible")
        addDependencies(extension.getPublish().getModrinth().getDependencies().getEmbedded().getOrElse(emptyList()), "embedded")

        val normalizedLoaders = MultiloaderPublishRules.modrinthNormalizeLoaders(publication.loaders)
        if (normalizedLoaders.isEmpty()) {
            throw IllegalStateException("[Publishing] Could not infer Modrinth loaders for ${file.name}")
        }

        val body = linkedMapOf<String, Any?>(
            "project_id" to projectId,
            "file_parts" to listOf(file.name),
            "version_number" to publication.project.version.toString(),
            "name" to publicationDisplayName(file, publication),
            "changelog" to changelog,
            "dependencies" to dependencies,
            "game_versions" to gameVersions,
            "version_type" to extension.getConfig().releaseType.get(),
            "loaders" to normalizedLoaders,
            "featured" to true,
            "status" to if (extension.getPublish().isManualRelease.get()) "draft" else "listed",
        )

        val parts = ModrinthPublishingClient.filePartsFrom(listOf(file))
        if (dryRun) {
            val info = client.createVersionMultipart(body, parts, true)
            project.logger.lifecycle(
                "[Publishing] Modrinth dryRun payload (${file.name}): ${JsonOutput.prettyPrint(JsonOutput.toJson(info))}",
            )
        } else {
            val response = client.createVersionMultipart(body, parts, false)
            project.logger.lifecycle(
                "[Publishing] Modrinth upload ok: version_id=${response["id"]}, " +
                    "version_number=${response["version_number"]} (${file.name})",
            )
        }
    }

    private fun publishCurseForge(
        project: Project,
        extension: MultiloaderPublishingExtension,
        changelog: String,
        dryRun: Boolean,
        gameVersions: List<String>,
        file: File,
        publication: PublicationSpec,
    ) {
        val relations = mutableListOf<Map<String, String>>()
        fun addRelations(slugs: List<String>, type: String) {
            slugs.forEach { slug -> relations.add(linkedMapOf("slug" to slug, "type" to type)) }
        }
        addRelations(extension.getPublish().getCurseforge().getDependencies().getRequired().getOrElse(emptyList()), "requiredDependency")
        addRelations(extension.getPublish().getCurseforge().getDependencies().getOptional().getOrElse(emptyList()), "optionalDependency")
        addRelations(extension.getPublish().getCurseforge().getDependencies().getIncompatible().getOrElse(emptyList()), "incompatible")
        addRelations(extension.getPublish().getCurseforge().getDependencies().getEmbedded().getOrElse(emptyList()), "embeddedLibrary")

        val curseTags = mutableListOf<String>()
        curseTags.addAll(gameVersions)
        curseTags.addAll(environmentTags(extension.getPublish().getCurseforge().environment.get()))
        curseTags.addAll(publication.javaVersions.map(::normalizeJavaTag))
        curseTags.addAll(MultiloaderPublishRules.curseNormalizeLoaders(publication.loaders))

        if (curseTags.isEmpty()) {
            throw IllegalStateException("[Publishing] Could not infer CurseForge loader tags for ${file.name}")
        }

        val metadata = linkedMapOf<String, Any?>(
            "changelog" to changelog,
            "changelogType" to "markdown",
            "displayName" to publicationDisplayName(file, publication),
            "gameVersions" to curseTags,
            "releaseType" to extension.getConfig().releaseType.get(),
            "isMarkedForManualRelease" to extension.getPublish().isManualRelease.get(),
        )
        if (relations.isNotEmpty()) {
            metadata["relations"] = linkedMapOf("projects" to relations)
        }

        if (dryRun) {
            val dryRunPayload = linkedMapOf(
                "projectId" to extension.getPublish().getCurseforge().id.get(),
                "gameVersions" to curseTags,
                "metadata" to metadata,
            )
            project.logger.lifecycle(
                "[Publishing] CurseForge dryRun payload (${file.name}): ${JsonOutput.prettyPrint(JsonOutput.toJson(dryRunPayload))}",
            )
        } else {
            val api = CurseUploadApi(extension.getPublish().getCurseforge().token.get())
            api.setDebug(false)

            val artifact = CurseArtifact(file, extension.getPublish().getCurseforge().id.get().toLong())
                .changelog(changelog)
                .changelogType(CurseChangelogType.MARKDOWN)
                .displayName(publicationDisplayName(file, publication))
                .releaseType(CurseReleaseType.valueOf(extension.getConfig().releaseType.get().uppercase(Locale.ROOT)))

            curseTags.forEach { tag -> artifact.addGameVersion(tag) }

            relations.forEach { relation ->
                when (relation["type"]) {
                    "requiredDependency" -> artifact.requirement(relation["slug"])
                    "optionalDependency" -> artifact.optional(relation["slug"])
                    "incompatible" -> artifact.incompatibility(relation["slug"])
                    "embeddedLibrary" -> artifact.embedded(relation["slug"])
                    else -> throw IllegalStateException("[Publishing] Unsupported CurseForge relation type: ${relation["type"]}")
                }
            }

            if (extension.getPublish().isManualRelease.get()) {
                artifact.manualRelease()
            }

            api.upload(artifact)
            project.logger.lifecycle("[Publishing] CurseForge upload ok: ${file.name}")
        }
    }

    private fun environmentTags(environment: String?): List<String> =
        when ((environment ?: "both").lowercase(Locale.ROOT)) {
            "client" -> listOf("client")
            "server" -> listOf("server")
            else -> listOf("client", "server")
        }

    private fun normalizeJavaTag(value: String): String {
        val normalized = value.trim()
        return if (normalized.lowercase(Locale.ROOT).startsWith("java ")) normalized else "Java $normalized"
    }

    private fun publicationDisplayName(file: File, publication: PublicationSpec): String =
        publication.displayName ?: file.name.replace(Regex("\\.jar$"), "")

    private fun resolveChangelog(
        project: Project,
        extension: MultiloaderPublishingExtension,
        publication: PublicationSpec,
    ): String {
        val finalFilePath = project.findProperty("publish.changelog.final-file")?.toString()
        if (!finalFilePath.isNullOrBlank()) {
            val finalFile = project.file(finalFilePath)
            if (!finalFile.exists()) {
                throw IllegalStateException("[Publishing] Final changelog file not found: $finalFile")
            }
            return finalFile.readText(Charsets.UTF_8)
        }

        if (extension.getMetadata().changelogText.isPresent) {
            return extension.getMetadata().changelogText.get()
        }

        val relPath = extension.getMetadata().changelogFile.orNull
        if (relPath.isNullOrBlank()) {
            throw IllegalStateException("[Publishing] No changelog configured")
        }

        val file = project.file(relPath)
        if (!file.exists()) {
            throw IllegalStateException("[Publishing] Changelog file not found: $file")
        }

        val extracted = extractSelectedChangelog(
            file.readText(Charsets.UTF_8),
            publication.project.version.toString(),
            publication.gameVersions,
        )
        if (extracted.isNullOrBlank()) {
            throw IllegalStateException(
                "[Publishing] Failed to extract changelog ${releaseVersionForChangelog(publication.project.version.toString())} from $file",
            )
        }
        return extracted
    }

    private fun extractSelectedChangelog(
        completeChangelog: String,
        projectVersion: String,
        minecraftVersions: List<String>,
    ): String? {
        val parsed = parseChangelog(completeChangelog) ?: return null
        val releaseVersion = releaseVersionForChangelog(projectVersion)
        val release = parsed.releases.firstOrNull { it.version == releaseVersion } ?: return null

        val selectedEntries = release.entries.filter { entry ->
            entry.prefix == null || minecraftVersions.any { version -> prefixMatchesMinecraft(entry.prefix, version) }
        }
        if (selectedEntries.isEmpty()) return null

        val lines = mutableListOf(release.heading)
        val sections = selectedEntries.map { it.section.orEmpty() }.distinct()
        sections.forEach { section ->
            val sectionEntries = selectedEntries.filter { it.section.orEmpty() == section }
            if (sectionEntries.isEmpty()) return@forEach
            lines.add("")
            if (section.isNotEmpty()) {
                lines.add("### $section")
                lines.add("")
            }
            sectionEntries.forEachIndexed { index, entry ->
                if (index > 0) lines.add("")
                lines.addAll(Pattern.compile("\\r?\\n").split(entry.text).toList())
            }
        }

        return listOf(
            trimTrailingBlankLines(Pattern.compile("\\r?\\n").split(parsed.header).toMutableList()).joinToString("\n"),
            trimTrailingBlankLines(lines).joinToString("\n"),
            parsed.footer,
        ).filter(String::isNotEmpty)
            .joinToString("\n\n")
            .trimEnd() + "\n"
    }

    private fun parseChangelog(completeChangelog: String): ParsedChangelog? {
        val lines = Pattern.compile("\\r?\\n").split(completeChangelog, -1).toList()
        val firstReleaseIndex = lines.indexOfFirst { releaseHeading(it) != null }
        val footerIndex = lines.indexOfFirst { it == "## Types of changes" }
        if (firstReleaseIndex < 0 || footerIndex <= firstReleaseIndex) return null

        val header = lines.subList(0, firstReleaseIndex).joinToString("\n")
        val footer = lines.subList(footerIndex, lines.size).joinToString("\n")
        val releases = mutableListOf<ParsedRelease>()
        var current: ParsedRelease? = null

        lines.subList(firstReleaseIndex, footerIndex).forEach { line ->
            val version = releaseHeading(line)
            if (version != null) {
                current = ParsedRelease(line, releaseVersionForChangelog(version), mutableListOf(line))
                releases.add(current!!)
            } else {
                current?.lines?.add(line)
            }
        }

        releases.forEach { release ->
            release.entries.addAll(entriesForRelease(release))
        }
        return ParsedChangelog(header, footer, releases)
    }

    private fun releaseHeading(line: String): String? =
        Regex("^##\\s+(\\d+\\.\\d+\\.\\d+(?:\\+[^\\s]+)?)(?:\\s|$)")
            .find(line)
            ?.groupValues
            ?.get(1)

    private fun entriesForRelease(release: ParsedRelease): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var section: String? = null
        var currentLines = mutableListOf<String>()
        var currentIndex = 0

        fun flush() {
            if (currentLines.isEmpty()) return
            val text = trimTrailingBlankLines(currentLines).joinToString("\n")
            if (text.trim().isNotEmpty()) {
                entries.add(
                    ChangelogEntry(
                        "${release.version}:${section ?: "release"}:$currentIndex",
                        release.version,
                        section,
                        text,
                        versionPrefixForEntry(text),
                    ),
                )
                currentIndex += 1
            }
            currentLines = mutableListOf()
        }

        release.lines.drop(1).forEach { line ->
            val sectionMatcher = Regex("^###\\s+(.+?)\\s*$").find(line)
            when {
                sectionMatcher != null -> {
                    flush()
                    section = sectionMatcher.groupValues[1]
                    currentIndex = 0
                }
                Regex("^[-*+]\\s+.*").matches(line) -> {
                    flush()
                    currentLines = mutableListOf(line)
                }
                currentLines.isNotEmpty() -> currentLines.add(line)
            }
        }
        flush()

        return entries
    }

    private fun versionPrefixForEntry(text: String): String? {
        val firstLine = text.lineSequence().first()
        return Regex("^[-*+]\\s+((?:\\d+\\.\\d+(?:\\.\\d+|\\.x)?|26\\.\\d+(?:\\.\\d+|\\.x)?)):\\s+")
            .find(firstLine)
            ?.groupValues
            ?.get(1)
    }

    private fun prefixMatchesMinecraft(prefix: String, minecraftVersion: String): Boolean =
        if (prefix.endsWith(".x")) minecraftVersion.startsWith(prefix.substring(0, prefix.length - 1)) else prefix == minecraftVersion

    private fun releaseVersionForChangelog(projectVersion: String): String =
        projectVersion.substringBefore("+")

    private fun trimTrailingBlankLines(lines: List<String>): MutableList<String> {
        val result = ArrayList(lines)
        while (result.isNotEmpty() && result.last().trim().isEmpty()) {
            result.removeAt(result.size - 1)
        }
        return result
    }

    private fun curseEnvironment(project: Project): String {
        val client = (PublicationPlanner.optionalProperty(project, "environments.client") ?: "required").lowercase(Locale.ROOT)
        val server = (PublicationPlanner.optionalProperty(project, "environments.server") ?: "required").lowercase(Locale.ROOT)

        if (client == "required" && server == "required") return "both"
        if (client == "required" && server != "required") return "client"
        if (server == "required" && client != "required") return "server"
        return "both"
    }

    private data class ParsedChangelog(
        val header: String,
        val footer: String,
        val releases: List<ParsedRelease>,
    )

    private data class ParsedRelease(
        val heading: String,
        val version: String,
        val lines: MutableList<String>,
        val entries: MutableList<ChangelogEntry> = mutableListOf(),
    )

    private data class ChangelogEntry(
        val id: String,
        val releaseVersion: String,
        val section: String?,
        val text: String,
        val prefix: String?,
    )

    companion object {
        const val EXTENSION_NAME = "multiloaderPublishing"
    }
}
