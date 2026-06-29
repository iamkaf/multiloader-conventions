package com.iamkaf.multiloader.publishing

import org.gradle.api.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object ArtifactStager {
    fun stage(project: Project, extension: MultiloaderPublishingExtension, spec: PublicationSpec): File {
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
        return dest
    }
}
