package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.VersionPolicy
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
        val metadataFile = File(versionDir, "gradle.properties")
        if (metadataFile.isFile) {
            return loadProperties(metadataFile)
        }
        return VersionPolicy.metadataProperties(versionDir.name)
    }

    fun enabledLoadersByVersion(versionDirs: List<File>): Map<String, List<String>> =
        versionDirs.associate { dir -> dir.name to parseEnabledLoaders(versionMetadata(dir)) }

    fun parseEnabledLoaders(props: Properties): List<String> =
        props.getProperty("project.enabled-loaders", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun loadProperties(file: File): Properties =
        Properties().also { props -> file.inputStream().use(props::load) }
}
