package com.iamkaf.multiloader.publishing

import java.io.File

internal object ModrinthReleaseBuilder {
    fun buildBody(
        projectId: String,
        changelog: String,
        gameVersions: List<String>,
        file: File,
        publication: PublicationSpec,
        extension: MultiloaderPublishingExtension,
        dependencies: List<Map<String, String>>,
    ): Map<String, Any?> {
        val normalizedLoaders = MultiloaderPublishRules.modrinthNormalizeLoaders(publication.loaders)
        if (normalizedLoaders.isEmpty()) {
            throw IllegalStateException("[Publishing] Could not infer Modrinth loaders for ${file.name}")
        }

        return linkedMapOf(
            "project_id" to projectId,
            "file_parts" to listOf(file.name),
            "version_number" to publication.project.version.toString(),
            "name" to PublishingDisplay.publicationDisplayName(file, publication),
            "changelog" to changelog,
            "dependencies" to dependencies,
            "game_versions" to gameVersions,
            "version_type" to extension.getConfig().releaseType.get(),
            "loaders" to normalizedLoaders,
            "featured" to true,
            "status" to if (extension.getPublish().isManualRelease.get()) "draft" else "listed",
        )
    }
}
