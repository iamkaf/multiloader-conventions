package com.iamkaf.multiloader.support

import java.io.File
import java.util.Properties

data class VersionMetadataDifference(
    val key: String,
    val expected: String?,
    val actual: String?,
)

object VersionMetadataFiles {
    val managedKeys: List<String> = listOf(
        "project.minecraft",
        "project.java",
        "project.build-java",
        "project.enabled-loaders",
        "project.catalog-name",
        "project.catalog-coordinate",
        "mod.minecraft-range",
        "mod.fabric-range",
        "mod.forge-loader-range",
        "mod.neoforge-loader-range",
        "mixin.compat.common",
        "mixin.compat.fabric",
        "mixin.compat.forge",
        "mixin.compat.neoforge",
    )

    private val materializedGroups: List<List<String>> = listOf(
        listOf(
            "project.version",
            "project.java",
            "project.build-java",
            "project.minecraft",
            "project.enabled-loaders",
            "project.catalog-name",
            "project.catalog-coordinate",
        ),
        listOf(
            "mixin.compat.common",
            "mixin.compat.fabric",
            "mixin.compat.forge",
            "mixin.compat.neoforge",
        ),
        listOf(
            "mod.minecraft-range",
            "mod.fabric-range",
            "mod.forge-loader-range",
            "mod.neoforge-loader-range",
        ),
        listOf(
            "publish.game-versions",
            "dependencies.modrinth.required",
            "dependencies.curseforge.required",
        ),
    )

    fun versionMetadata(versionDir: File): Properties {
        val props = policyMetadata(versionDir.name)
        val metadataFile = File(versionDir, "gradle.properties")
        if (metadataFile.isFile) {
            metadataFile.inputStream().use(props::load)
        }
        return props
    }

    fun policyMetadata(version: String): Properties = VersionPolicy.metadataProperties(version)

    fun differences(versionDir: File): List<VersionMetadataDifference> {
        val metadataFile = File(versionDir, "gradle.properties")
        val actual = Properties()
        if (metadataFile.isFile) {
            metadataFile.inputStream().use(actual::load)
        }
        val expected = policyMetadata(versionDir.name)

        return managedKeys.mapNotNull { key ->
            val expectedValue = expected.getProperty(key)
            val actualValue = actual.getProperty(key)
            if (expectedValue != actualValue) {
                VersionMetadataDifference(key, expectedValue, actualValue)
            } else {
                null
            }
        }
    }

    fun writeMaterializedMetadata(versionDir: File) {
        val metadataFile = File(versionDir, "gradle.properties")
        val existing = Properties()
        if (metadataFile.isFile) {
            metadataFile.inputStream().use(existing::load)
        }
        val policy = policyMetadata(versionDir.name)
        val lines = mutableListOf<String>()
        val emitted = mutableSetOf<String>()

        materializedGroups.forEach { group ->
            val groupLines = group.mapNotNull { key ->
                val value = if (key in managedKeys) policy.getProperty(key) else existing.getProperty(key)
                if (value == null) {
                    null
                } else {
                    emitted += key
                    "${escape(key)}=${escape(value)}"
                }
            }
            if (groupLines.isNotEmpty()) {
                if (lines.isNotEmpty()) {
                    lines += ""
                }
                lines += groupLines
            }
        }

        val unmanagedKeys = existing.stringPropertyNames()
            .filter { it !in emitted && it !in managedKeys }
            .sorted()
        if (unmanagedKeys.isNotEmpty()) {
            lines += ""
            unmanagedKeys.forEach { key ->
                lines += "${escape(key)}=${escape(existing.getProperty(key))}"
            }
        }

        metadataFile.parentFile.mkdirs()
        metadataFile.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator())
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
}
