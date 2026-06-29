package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.VersionMetadataFiles
import org.gradle.api.Project
import java.io.File
import java.util.Properties

object RootVersionMatrix {
    fun hasVersionMatrix(project: Project): Boolean = versionDirectories(project).isNotEmpty()

    fun versionDirectories(project: Project): List<File> {
        val versionsDir = project.file("versions")
        if (!versionsDir.isDirectory) return emptyList()
        return versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun versionMetadata(versionDir: File): Properties {
        return VersionMetadataFiles.versionMetadata(versionDir)
    }

    fun enabledLoadersByVersion(versionDirs: List<File>): Map<String, List<String>> =
        versionDirs.associate { dir -> dir.name to parseEnabledLoaders(versionMetadata(dir)) }

    fun parseEnabledLoaders(props: Properties): List<String> =
        props.getProperty("project.enabled-loaders", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun metadataDifferences(versionDir: File) = VersionMetadataFiles.differences(versionDir)

    fun writeMaterializedMetadata(versionDir: File) = VersionMetadataFiles.writeMaterializedMetadata(versionDir)
}
