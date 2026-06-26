package com.iamkaf.multiloader.publishing

import java.io.File
import java.util.Locale

internal object CurseForgeReleaseBuilder {
    fun buildMetadata(
        changelog: String,
        gameVersions: List<String>,
        file: File,
        publication: PublicationSpec,
        extension: MultiloaderPublishingExtension,
        relations: List<Map<String, String>>,
    ): Map<String, Any?> {
        val curseTags = curseTags(gameVersions, publication, extension)
        if (curseTags.isEmpty()) {
            throw IllegalStateException("[Publishing] Could not infer CurseForge loader tags for ${file.name}")
        }

        val metadata = linkedMapOf<String, Any?>(
            "changelog" to changelog,
            "changelogType" to "markdown",
            "displayName" to PublishingDisplay.publicationDisplayName(file, publication),
            "gameVersions" to curseTags,
            "releaseType" to extension.getConfig().releaseType.get(),
            "isMarkedForManualRelease" to extension.getPublish().isManualRelease.get(),
        )
        if (relations.isNotEmpty()) {
            metadata["relations"] = linkedMapOf("projects" to relations)
        }
        return metadata
    }

    fun dryRunPayload(
        projectId: String,
        gameVersions: List<String>,
        metadata: Map<String, Any?>,
        publication: PublicationSpec,
        extension: MultiloaderPublishingExtension,
    ): Map<String, Any?> =
        linkedMapOf(
            "projectId" to projectId,
            "gameVersions" to curseTags(gameVersions, publication, extension),
            "metadata" to metadata,
        )

    fun relations(extension: MultiloaderPublishingExtension): List<Map<String, String>> {
        val relations = mutableListOf<Map<String, String>>()
        fun addRelations(slugs: List<String>, type: String) {
            slugs.forEach { slug -> relations.add(linkedMapOf("slug" to slug, "type" to type)) }
        }
        addRelations(extension.getPublish().getCurseforge().getDependencies().getRequired().getOrElse(emptyList()), "requiredDependency")
        addRelations(extension.getPublish().getCurseforge().getDependencies().getOptional().getOrElse(emptyList()), "optionalDependency")
        addRelations(extension.getPublish().getCurseforge().getDependencies().getIncompatible().getOrElse(emptyList()), "incompatible")
        addRelations(extension.getPublish().getCurseforge().getDependencies().getEmbedded().getOrElse(emptyList()), "embeddedLibrary")
        return relations
    }

    fun curseTags(
        gameVersions: List<String>,
        publication: PublicationSpec,
        extension: MultiloaderPublishingExtension,
    ): List<String> {
        val curseTags = mutableListOf<String>()
        curseTags.addAll(gameVersions)
        curseTags.addAll(environmentTags(extension.getPublish().getCurseforge().environment.get()))
        curseTags.addAll(publication.javaVersions.map(::normalizeJavaTag))
        curseTags.addAll(MultiloaderPublishRules.curseNormalizeLoaders(publication.loaders))
        return curseTags
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
}
