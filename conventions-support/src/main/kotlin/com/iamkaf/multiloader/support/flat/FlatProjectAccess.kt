package com.iamkaf.multiloader.support.flat

import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.io.File

object FlatProjectAccess {
    fun commonFile(project: Project, relativePath: String): File {
        val commonProject = project.rootProject.findProject(":common")
        return commonProject?.file(relativePath) ?: project.file(relativePath)
    }

    fun collectMixinConfigs(project: Project, loaderName: String): List<String> {
        val modId = requiredProperty(project, "mod.id")
        val mixinConfigs = mutableListOf<String>()
        val commonMixin = commonFile(project, "src/main/resources/$modId.mixins.json")
        if (commonMixin.exists()) {
            mixinConfigs.add("$modId.mixins.json")
        }
        val loaderMixin = project.file("src/main/resources/$modId.$loaderName.mixins.json")
        if (loaderMixin.exists()) {
            mixinConfigs.add("$modId.$loaderName.mixins.json")
        }
        return mixinConfigs
    }

    fun resolveLicenseFile(project: Project): File {
        val repoRootLicense = project.rootProject.file("../LICENSE")
        return if (repoRootLicense.exists()) repoRootLicense else project.rootProject.file("LICENSE")
    }

    fun versionCatalog(project: Project): VersionCatalog {
        val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java)
            ?: throw IllegalStateException("Missing version catalogs for ${project.path}")
        return catalogs.named("libs")
    }

    fun requiredLibrary(project: Project, alias: String): Any {
        val library = versionCatalog(project).findLibrary(alias)
        if (!library.isPresent) {
            throw IllegalStateException("Missing required library alias '$alias' for ${project.path}")
        }
        return library.get().get()
    }

    fun versionAlias(project: Project, alias: String): String {
        val value = optionalVersionAlias(project, alias)
        if (value.isNullOrBlank()) {
            throw IllegalStateException("Missing required version catalog alias '$alias' for ${project.path}")
        }
        return value
    }

    fun optionalVersionAlias(project: Project, alias: String): String? {
        val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java) ?: return null
        val version = catalogs.named("libs").findVersion(alias)
        if (!version.isPresent) return null

        val resolved = version.get().requiredVersion
        return resolved?.takeUnless { it.isBlank() || it == "null" }
    }

    fun requiredProperty(project: Project, propertyName: String): String {
        val value = optionalProperty(project, propertyName)
        if (value.isNullOrBlank()) {
            throw IllegalStateException("Missing required property '$propertyName' for ${project.path}")
        }
        return value
    }

    fun optionalProperty(project: Project, propertyName: String): String? =
        project.findProperty(propertyName)?.toString()

    fun isUnobfuscatedMinecraft(project: Project): Boolean {
        val minecraftVersion = optionalProperty(project, "project.minecraft")
        return minecraftVersion != null && VersionPolicy.useUnobfuscatedMinecraft(minecraftVersion)
    }
}
