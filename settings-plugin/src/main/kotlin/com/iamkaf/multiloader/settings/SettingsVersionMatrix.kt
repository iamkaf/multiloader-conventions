package com.iamkaf.multiloader.settings

import com.iamkaf.multiloader.support.LoaderId
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.initialization.Settings
import java.io.File
import java.util.Properties

object SettingsVersionMatrix {
    fun versionDirectories(settings: Settings): List<File> {
        val versionsDir = File(settings.settingsDir, "versions")
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

        val props = Properties()
        props.setProperty("project.enabled-loaders", VersionPolicy.enabledLoaderIds(versionDir.name))
        return props
    }

    fun parseEnabledLoaders(settings: Settings): List<String> {
        val raw = settings.providers.gradleProperty("project.enabled-loaders")
            .orElse("fabric,forge,neoforge")
            .get()
        val props = Properties()
        props.setProperty("project.enabled-loaders", raw)
        return parseEnabledLoaders(props)
    }

    fun enabledLoadersByVersion(versionDirs: List<File>): Map<String, List<String>> =
        versionDirs.associate { dir -> dir.name to parseEnabledLoaders(versionMetadata(dir)) }

    fun parseEnabledLoaders(props: Properties): List<String> =
        props.getProperty("project.enabled-loaders", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { LoaderId.parse(it) != null }

    fun catalogCoordinate(props: Properties, mcVersion: String): String =
        props.getProperty("project.catalog-coordinate")
            ?.takeIf { it.isNotBlank() }
            ?: VersionPolicy.catalogCoordinate(mcVersion)

    private fun loadProperties(file: File): Properties =
        Properties().also { properties ->
            file.inputStream().use(properties::load)
        }
}
