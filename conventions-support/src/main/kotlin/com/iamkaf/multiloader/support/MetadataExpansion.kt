package com.iamkaf.multiloader.support

import com.iamkaf.multiloader.support.flat.FlatProjectAccess
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog

object MetadataExpansion {
    fun stonecutter(
        context: MultiloaderProjectContext,
        minecraftVersion: String,
        loader: String,
        catalog: VersionCatalog,
    ): Map<String, Any?> {
        val minecraftVersionRange = if (loader == LoaderId.FABRIC.id) {
            fabricMinecraftDependency(minecraftVersion, context.optionalProperty("mod.minecraft-range"))
        } else {
            context.optionalProperty("mod.minecraft-range")
        }

        return linkedMapOf(
            "version" to context.requiredProperty("project.version"),
            "group" to context.requiredProperty("project.group"),
            "minecraft_version" to minecraftVersion,
            "minecraft_version_range" to minecraftVersionRange,
            "fabric_version_range" to context.optionalProperty("mod.fabric-range"),
            "fabric_version" to context.versionOrNull(catalog, "fabric-api"),
            "fabric_loader_version" to context.versionOrNull(catalog, "fabric-loader"),
            "mod_menu_version" to context.versionOrNull(catalog, "modmenu"),
            "mod_name" to context.requiredProperty("mod.name"),
            "mod_author" to context.optionalProperty("mod.authors"),
            "mod_id" to context.requiredProperty("mod.id"),
            "license" to context.optionalProperty("mod.license"),
            "description" to context.optionalProperty("mod.description"),
            "neoforge_version" to context.versionOrNull(catalog, "neoforge"),
            "neoforge_loader_version_range" to context.optionalProperty("mod.neoforge-loader-range"),
            "forge_version" to context.versionOrNull(catalog, "forge"),
            "forge_loader_version_range" to context.optionalProperty("mod.forge-loader-range"),
            "amber_version" to context.versionOrNull(catalog, "amber"),
            "konfig_version" to context.versionOrNull(catalog, "konfig"),
            "parchment_minecraft" to context.versionOrNull(catalog, "parchment-minecraft"),
            "parchment_version" to context.versionOrNull(catalog, "parchment"),
            "credits" to context.optionalProperty("mod.credits"),
            "java_version" to context.requiredProperty("project.java"),
            "mixin_compat_common" to context.requiredProperty("mixin.compat.common"),
            "mixin_compat_fabric" to context.requiredProperty("mixin.compat.fabric"),
            "mixin_compat_forge" to context.requiredProperty("mixin.compat.forge"),
            "mixin_compat_neoforge" to context.requiredProperty("mixin.compat.neoforge"),
            "pack_format" to VersionPolicy.resourcePackFormat(minecraftVersion),
            "pack_minmax" to VersionPolicy.resourcePackMinMaxSnippet(minecraftVersion),
            "pack_description" to "${context.requiredProperty("mod.name")} resources",
        )
    }

    fun flat(project: Project): Map<String, Any?> =
        linkedMapOf(
            "version" to project.version.toString(),
            "group" to project.group.toString(),
            "minecraft_version" to FlatProjectAccess.versionAlias(project, "minecraft"),
            "minecraft_version_range" to FlatProjectAccess.requiredProperty(project, "mod.minecraft-range"),
            "fabric_version_range" to FlatProjectAccess.optionalProperty(project, "mod.fabric-range"),
            "fabric_version" to FlatProjectAccess.optionalVersionAlias(project, "fabric-api"),
            "fabric_loader_version" to FlatProjectAccess.optionalVersionAlias(project, "fabric-loader"),
            "mod_menu_version" to FlatProjectAccess.optionalVersionAlias(project, "modmenu"),
            "mod_name" to FlatProjectAccess.requiredProperty(project, "mod.name"),
            "mod_author" to FlatProjectAccess.optionalProperty(project, "mod.authors"),
            "mod_id" to FlatProjectAccess.requiredProperty(project, "mod.id"),
            "license" to FlatProjectAccess.optionalProperty(project, "mod.license"),
            "description" to FlatProjectAccess.optionalProperty(project, "mod.description"),
            "neoforge_version" to FlatProjectAccess.optionalVersionAlias(project, "neoforge"),
            "neoforge_loader_version_range" to FlatProjectAccess.optionalProperty(project, "mod.neoforge-loader-range"),
            "forge_version" to FlatProjectAccess.optionalVersionAlias(project, "forge"),
            "forge_loader_version_range" to FlatProjectAccess.optionalProperty(project, "mod.forge-loader-range"),
            "amber_version" to FlatProjectAccess.optionalVersionAlias(project, "amber"),
            "konfig_version" to FlatProjectAccess.optionalVersionAlias(project, "konfig"),
            "parchment_minecraft" to FlatProjectAccess.optionalVersionAlias(project, "parchment-minecraft"),
            "parchment_version" to FlatProjectAccess.optionalVersionAlias(project, "parchment"),
            "credits" to FlatProjectAccess.optionalProperty(project, "mod.credits"),
            "java_version" to FlatProjectAccess.requiredProperty(project, "project.java"),
            "pack_format" to VersionPolicy.resourcePackFormat(FlatProjectAccess.versionAlias(project, "minecraft")),
            "pack_minmax" to VersionPolicy.resourcePackMinMaxSnippet(FlatProjectAccess.versionAlias(project, "minecraft")),
            "pack_description" to "${FlatProjectAccess.requiredProperty(project, "mod.name")} resources",
        )

    fun jsonSafe(expandProperties: Map<String, Any?>): Map<String, Any?> =
        expandProperties.mapValues { (_, value) ->
            if (value is String) value.replace("\n", "\\n") else value
        }

    private fun fabricMinecraftDependency(minecraftVersion: String?, configuredRange: String?): String? =
        if (minecraftVersion == null || minecraftVersion.contains("-rc-")) configuredRange else minecraftVersion
}
