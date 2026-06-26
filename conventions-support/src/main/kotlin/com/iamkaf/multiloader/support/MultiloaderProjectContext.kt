package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import java.util.Properties

class MultiloaderProjectContext private constructor(private val project: Project) {
    companion object {
        @JvmStatic
        fun of(project: Project): MultiloaderProjectContext = MultiloaderProjectContext(project)
    }

    fun requiredProperty(name: String): String =
        optionalProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Missing required property '$name' for ${project.path}")

    fun optionalProperty(name: String): String? {
        if (name == "loader") {
            return project.parent?.name
        }

        val versionKey = resolveVersionKey()
        if (!versionKey.isNullOrBlank()) {
            val versionProps = versionProperties(versionKey)
            val versionValue = versionProps.getProperty(name)
            if (!versionValue.isNullOrBlank()) {
                return versionValue
            }
        }

        val directValue = scopedProperty(name)
        if (!directValue.isNullOrBlank()) {
            if (name == "publish.game-versions" && directValue == "auto") {
                return optionalProperty("project.minecraft")
            }
            return directValue
        }

        return null
    }

    fun minecraftVersion(): String = requiredProperty("project.minecraft")

    fun loader(): String = requiredProperty("loader")

    fun catalogFor(minecraftVersion: String = minecraftVersion()): VersionCatalog {
        val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
        val versionCatalog = catalogs.find(VersionPolicy.catalogName(minecraftVersion))
        return if (versionCatalog.isPresent) versionCatalog.get() else catalogs.named("libs")
    }

    fun versionOrNull(catalog: VersionCatalog, alias: String): String? {
        val version = catalog.findVersion(alias)
        if (!version.isPresent) return null
        val resolved = version.get().requiredVersion
        return resolved?.takeUnless { it.isBlank() || it == "null" }
    }

    fun library(catalog: VersionCatalog, alias: String): Any {
        val dependency = catalog.findLibrary(alias)
        if (!dependency.isPresent) {
            throw GradleException("Missing library alias '$alias'")
        }
        return dependency.get()
    }

    fun useUnobfuscatedMinecraft(minecraftVersion: String = minecraftVersion()): Boolean =
        VersionPolicy.useUnobfuscatedMinecraft(minecraftVersion)

    fun sharedRepositories() = RepositoryPolicy.configureProjectRepositories(project)

    fun publishingRepositories(publishing: PublishingExtension, version: String) {
        RepositoryPolicy.configurePublishingRepositories(publishing, version)
    }

    fun mixinConfigs(loader: String): List<String> {
        val modId = requiredProperty("mod.id")
        return listOfNotNull(
            if (project.rootProject.file("common/src/main/resources/$modId.mixins.json").exists()) {
                "$modId.mixins.json"
            } else {
                null
            },
            if (project.rootProject.file("$loader/src/main/resources/$modId.$loader.mixins.json").exists()) {
                "$modId.$loader.mixins.json"
            } else {
                null
            },
        )
    }

    fun expandProperties(minecraftVersion: String, loader: String, catalog: VersionCatalog): Map<String, Any?> =
        MetadataExpansion.stonecutter(this, minecraftVersion, loader, catalog)

    private fun resolveVersionKey(): String? {
        val directVersionDir = project.rootProject.file("versions/${project.name}")
        if (directVersionDir.isDirectory) {
            return project.name
        }

        scopedProperty("multiloader.stonecutter.active")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }

        val targetVersions = scopedProperty(MultiloaderTargetScope.VERSIONS_PROPERTY)?.trim()
        if (!targetVersions.isNullOrBlank() && !targetVersions.equals("all", ignoreCase = true)) {
            val targets = targetVersions.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
                .distinct()
            if (targets.size == 1) {
                return targets.first()
            }
        }

        val stonecutterBuild = project.extensions.findByName("stonecutterBuild")
            ?: project.extensions.findByName("stonecutter")
        val currentVersion = stonecutterBuild?.let { extension ->
            runCatching {
                val current = extension.javaClass.methods.firstOrNull { it.name == "getCurrent" && it.parameterCount == 0 }
                    ?.invoke(extension)
                    ?: extension.javaClass.fields.firstOrNull { it.name == "current" }?.get(extension)
                val version = current?.javaClass?.methods?.firstOrNull { it.name == "getVersion" && it.parameterCount == 0 }
                    ?.invoke(current)
                    ?: current?.javaClass?.fields?.firstOrNull { it.name == "version" }?.get(current)
                version?.toString()
            }.getOrNull()
        }

        return currentVersion?.takeIf { it.isNotBlank() }
    }

    private fun versionProperties(versionKey: String): Properties {
        val cacheKey = "versionProps:$versionKey"
        val extras = project.rootProject.extensions.extraProperties
        if (extras.has(cacheKey)) {
            return extras.get(cacheKey) as Properties
        }

        val properties = Properties()
        val versionFile = project.rootProject.file("versions/$versionKey/gradle.properties")
        if (versionFile.isFile) {
            versionFile.inputStream().use(properties::load)
        } else {
            properties.putAll(VersionPolicy.metadataProperties(versionKey))
        }
        extras.set(cacheKey, properties)
        return properties
    }

    private fun scopedProperty(name: String): String? =
        (project.findProperty(name) ?: project.rootProject.findProperty(name))?.toString()
}
