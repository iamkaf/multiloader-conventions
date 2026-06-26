package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.Settings

class MultiloaderTargetScope private constructor(
    private val allVersions: Set<String>,
    private val requestedVersions: Set<String>,
    private val requestedLoaders: Set<String>,
    private val enabledLoadersByVersion: Map<String, List<String>>,
) {
    companion object {
        const val VERSIONS_PROPERTY = "multiloader.target.versions"
        const val LOADERS_PROPERTY = "multiloader.target.loaders"

        private val knownLoaders = setOf("fabric", "forge", "neoforge")

        @JvmStatic
        fun fromSettings(settings: Settings, enabledLoadersByVersion: Map<String, List<String>>): MultiloaderTargetScope =
            create(
                optionalProperty(settings, VERSIONS_PROPERTY),
                optionalProperty(settings, LOADERS_PROPERTY),
                enabledLoadersByVersion,
            )

        @JvmStatic
        fun fromProject(project: Project, enabledLoadersByVersion: Map<String, List<String>>): MultiloaderTargetScope =
            create(
                optionalProperty(project, VERSIONS_PROPERTY),
                optionalProperty(project, LOADERS_PROPERTY),
                enabledLoadersByVersion,
            )

        private fun create(
            rawVersions: String?,
            rawLoaders: String?,
            enabledLoadersByVersion: Map<String, List<String>>,
        ): MultiloaderTargetScope {
            val allVersions = enabledLoadersByVersion.keys
            val requestedVersions = parseCsv(rawVersions).filter { !it.equals("all", ignoreCase = true) }.toSet()
            val requestedLoaders = parseCsv(rawLoaders).filter { !it.equals("all", ignoreCase = true) }.toSet()

            val unknownVersions = requestedVersions - allVersions
            if (unknownVersions.isNotEmpty()) {
                throw GradleException("Unknown $VERSIONS_PROPERTY: ${unknownVersions.joinToString(", ")}")
            }

            val unknownLoaders = requestedLoaders - knownLoaders
            if (unknownLoaders.isNotEmpty()) {
                throw GradleException("Unknown $LOADERS_PROPERTY: ${unknownLoaders.joinToString(", ")}")
            }

            return MultiloaderTargetScope(allVersions, requestedVersions, requestedLoaders, enabledLoadersByVersion)
        }

        private fun parseCsv(value: String?): List<String> =
            value?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?: emptyList()

        private fun optionalProperty(settings: Settings, name: String): String? {
            val value = settings.providers.gradleProperty(name).orNull
            return value?.takeUnless { it.isBlank() }
        }

        private fun optionalProperty(project: Project, name: String): String? {
            val value = project.findProperty(name) ?: project.rootProject.findProperty(name)
            return value?.toString()?.takeUnless { it.isBlank() }
        }
    }

    fun includesVersion(version: String): Boolean =
        requestedVersions.isEmpty() || requestedVersions.contains(version)

    fun includesLoader(loader: String): Boolean =
        requestedLoaders.isEmpty() || requestedLoaders.contains(loader)

    fun loadersFor(version: String): List<String> {
        if (!includesVersion(version)) {
            return emptyList()
        }

        val enabledLoaders = enabledLoadersByVersion[version].orEmpty()
        return if (requestedLoaders.isEmpty()) {
            enabledLoaders
        } else {
            enabledLoaders.filter { requestedLoaders.contains(it) }
        }
    }

    fun selectedVersions(): List<String> =
        allVersions.filter { includesVersion(it) }
}
