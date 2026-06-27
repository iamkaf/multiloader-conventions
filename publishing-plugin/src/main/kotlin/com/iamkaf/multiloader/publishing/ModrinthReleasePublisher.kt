package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import org.gradle.api.Project
import java.io.File

internal object ModrinthReleasePublisher {
    fun publish(
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

        val body = ModrinthReleaseBuilder.buildBody(
            projectId = projectId,
            changelog = changelog,
            gameVersions = gameVersions,
            file = file,
            publication = publication,
            extension = extension,
            dependencies = dependencies(extension, client, dryRun),
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

    private fun dependencies(
        extension: MultiloaderPublishingExtension,
        client: ModrinthPublishingClient,
        dryRun: Boolean,
    ): List<Map<String, String>> {
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
        return dependencies
    }
}
