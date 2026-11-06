package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import java.io.File
import java.security.MessageDigest

/** A dependency on another project, by slug, as the platform names it. */
data class ManifestDependency(val slug: String, val type: String)

/**
 * One staged jar and everything a platform upload sends for it.
 *
 * [environment] is `client`, `server`, or `both`, the CurseForge environment tags the upload sends.
 * [horizontal] is true for one jar that covers several loaders.
 */
data class ManifestFile(
    val path: String,
    val sha256: String,
    val size: Long,
    val minecraft: String,
    val version: String,
    val displayName: String,
    val loaders: List<String>,
    val horizontal: Boolean,
    val modrinthGameVersions: List<String>,
    val curseforgeGameVersions: List<String>,
    val java: Int,
    val environment: String,
    val modrinthDependencies: List<ManifestDependency>,
    val curseforgeDependencies: List<ManifestDependency>,
)

data class ReleaseManifest(
    val mod: String,
    val commit: String,
    val modrinthId: String?,
    val curseforgeId: String?,
    val releaseType: String,
    val files: List<ManifestFile>,
)

object ReleaseManifestWriter {
    const val SCHEMA = 1
    private val releaseTypes = setOf("release", "beta", "alpha")

    @JvmStatic
    fun toJson(manifest: ReleaseManifest): String {
        if (manifest.releaseType !in releaseTypes) {
            throw IllegalStateException(
                "[Publishing] Unsupported release type '${manifest.releaseType}', expected one of $releaseTypes",
            )
        }
        if (manifest.files.isEmpty()) {
            throw IllegalStateException("[Publishing] No files selected for the release manifest")
        }
        val duplicate = manifest.files.groupBy { it.path }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicate != null) {
            throw IllegalStateException("[Publishing] More than one file would upload as $duplicate")
        }

        val document = linkedMapOf<String, Any?>(
            "schema" to SCHEMA,
            "mod" to manifest.mod,
            "commit" to manifest.commit,
            "modrinthId" to manifest.modrinthId,
            "curseforgeId" to manifest.curseforgeId,
            "releaseType" to manifest.releaseType,
            "files" to manifest.files.map(::fileJson),
        )
        return JsonOutput.prettyPrint(JsonOutput.toJson(document)) + System.lineSeparator()
    }

    @JvmStatic
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fileJson(file: ManifestFile): Map<String, Any?> =
        linkedMapOf(
            "path" to file.path,
            "sha256" to file.sha256,
            "size" to file.size,
            "minecraft" to file.minecraft,
            "version" to file.version,
            "displayName" to file.displayName,
            "loaders" to file.loaders,
            "horizontal" to file.horizontal,
            "gameVersions" to linkedMapOf(
                "modrinth" to file.modrinthGameVersions,
                "curseforge" to file.curseforgeGameVersions,
            ),
            "java" to file.java,
            "environment" to file.environment,
            "dependencies" to linkedMapOf(
                "modrinth" to file.modrinthDependencies.map(::dependencyJson),
                "curseforge" to file.curseforgeDependencies.map(::dependencyJson),
            ),
        )

    private fun dependencyJson(dependency: ManifestDependency): Map<String, String> =
        linkedMapOf("slug" to dependency.slug, "type" to dependency.type)
}
