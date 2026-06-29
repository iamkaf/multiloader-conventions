package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import me.hypherionmc.curseupload.CurseUploadApi
import me.hypherionmc.curseupload.constants.CurseChangelogType
import me.hypherionmc.curseupload.constants.CurseReleaseType
import me.hypherionmc.curseupload.requests.CurseArtifact
import org.gradle.api.Project
import java.io.File
import java.util.Locale

internal object CurseForgeReleasePublisher {
    fun publish(
        project: Project,
        extension: MultiloaderPublishingExtension,
        changelog: String,
        dryRun: Boolean,
        gameVersions: List<String>,
        file: File,
        publication: PublicationSpec,
    ) {
        val relations = CurseForgeReleaseBuilder.relations(extension)
        val metadata = CurseForgeReleaseBuilder.buildMetadata(
            changelog = changelog,
            gameVersions = gameVersions,
            file = file,
            publication = publication,
            extension = extension,
            relations = relations,
        )
        val curseTags = CurseForgeReleaseBuilder.curseTags(gameVersions, publication, extension)

        if (dryRun) {
            val dryRunPayload = CurseForgeReleaseBuilder.dryRunPayload(
                projectId = extension.getPublish().getCurseforge().id.get(),
                gameVersions = gameVersions,
                metadata = metadata,
                publication = publication,
                extension = extension,
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
                .displayName(PublishingDisplay.publicationDisplayName(file, publication))
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
}
