package com.iamkaf.multiloader.support

import org.gradle.api.Project

enum class MultiloaderProjectRole(val loaderId: LoaderId?, val artifactSuffix: String) {
    COMMON(null, "common"),
    FABRIC(LoaderId.FABRIC, LoaderId.FABRIC.id),
    FORGE(LoaderId.FORGE, LoaderId.FORGE.id),
    NEOFORGE(LoaderId.NEOFORGE, LoaderId.NEOFORGE.id),
}

data class ProjectIdentity(
    val group: String,
    val version: String,
    val modId: String,
    val modName: String,
    val authors: String?,
    val minecraftVersion: String,
    val javaVersion: Int,
    val role: MultiloaderProjectRole,
    /** Base of published artifact names; `mod.artifact` when the mod id cannot be the Maven name, since mod ids forbid hyphens. */
    val artifactBase: String = modId,
) {
    val loader: String?
        get() = role.loaderId?.id

    val archiveName: String
        get() = "$artifactBase-${role.artifactSuffix}"

    val implementationTitle: String
        get() = loader ?: role.artifactSuffix

    fun applyCoordinates(project: Project) {
        project.group = group
        project.version = version
    }

    companion object {
        @JvmStatic
        fun from(context: MultiloaderProjectContext, role: MultiloaderProjectRole): ProjectIdentity =
            ProjectIdentity(
                group = context.requiredProperty("project.group"),
                version = context.requiredProperty("project.version"),
                modId = context.requiredProperty("mod.id"),
                modName = context.requiredProperty("mod.name"),
                authors = context.optionalProperty("mod.authors"),
                minecraftVersion = context.requiredProperty("project.minecraft"),
                javaVersion = context.requiredProperty("project.java").toInt(),
                role = role,
                artifactBase = context.optionalProperty("mod.artifact")?.takeUnless { it.isBlank() } ?: context.requiredProperty("mod.id"),
            )
    }
}
