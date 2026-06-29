package com.iamkaf.multiloader.publishing

import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Locale

object MultiloaderPublishRules {
    @JvmStatic
    fun requireNonEmpty(label: String, items: Collection<*>?) {
        if (items == null || items.isEmpty()) {
            throw IllegalStateException("[Publishing] $label is required")
        }
    }

    @JvmStatic
    fun checkEmptyJar(jarFile: File?, loaders: List<String>?) {
        if (jarFile == null || !jarFile.exists()) {
            throw IllegalStateException("[Publishing] Jar does not exist: $jarFile")
        }
        if (loaders.isNullOrEmpty()) return

        FileSystems.newFileSystem(jarFile.toPath(), null as ClassLoader?).use { fs ->
            val quiltJson = fs.getPath("quilt.mod.json")
            val fabricJson = fs.getPath("fabric.mod.json")
            val forgeToml = fs.getPath("META-INF/mods.toml")
            val neoforgeToml = fs.getPath("META-INF/neoforge.mods.toml")
            val forgeMc = fs.getPath("mcmod.info")

            if ((loaders.contains("forge") || loaders.contains("neoforge")) &&
                !Files.exists(neoforgeToml) &&
                !Files.exists(forgeToml) &&
                !Files.exists(forgeMc)
            ) {
                throw IllegalStateException(
                    "[Publishing] File marked as forge/neoforge, but no neoforge.mods.toml, mods.toml, or mcmod.info file was found",
                )
            }

            if (loaders.contains("fabric") && !Files.exists(fabricJson)) {
                throw IllegalStateException("[Publishing] File marked as fabric, but no fabric.mod.json file was found")
            }

            if (loaders.contains("quilt") && !Files.exists(quiltJson) && !Files.exists(fabricJson)) {
                throw IllegalStateException(
                    "[Publishing] File marked as quilt, but no quilt.mod.json or fabric.mod.json file was found",
                )
            }
        }
    }

    @JvmStatic
    fun modrinthNormalizeGameVersions(gameVersions: List<String>?): List<String> =
        gameVersions.orEmpty()
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .filterNot { it.endsWith("-snapshot") }
            .map { it.lowercase(Locale.ROOT) }

    @JvmStatic
    fun modrinthNormalizeLoaders(loaders: List<String>?): List<String> {
        val out = mutableListOf<String>()
        loaders.orEmpty().forEach { loaderValue ->
            val loader = loaderValue.trim()
            if (loader.isEmpty()) return@forEach

            if (loader.equals("risugami's modloader", ignoreCase = true)) {
                if ("modloader" !in out) out.add("modloader")
                return@forEach
            }

            out.add(loader.lowercase(Locale.ROOT))
        }
        return out
    }

    @JvmStatic
    fun curseNormalizeGameVersions(gameVersions: List<String>?): List<String> {
        val out = mutableListOf<String>()
        val pattern = Regex("[A-Za-z0-9]+")

        gameVersions.orEmpty().forEach { versionValue ->
            val version = versionValue.trim()
            if (version.isEmpty() || pattern.matches(version)) return@forEach

            if (Regex(".*-snapshot-\\d+$").matches(version)) {
                out.add(version.replace(Regex("-snapshot-\\d+$"), "-snapshot"))
                return@forEach
            }

            if (version.contains("-pre") || version.contains("-rc")) return@forEach

            val min = DefaultArtifactVersion("b1.6.6")
            val current = DefaultArtifactVersion(version)
            when {
                current < min -> out.add("beta 1.6.6")
                version.contains("b1") -> out.add(version.replace("b", "beta "))
                else -> out.add(version)
            }
        }

        return out
    }

    @JvmStatic
    fun curseNormalizeLoaders(loaders: List<String>?): List<String> {
        val out = mutableListOf<String>()
        loaders.orEmpty().forEach { loaderValue ->
            when {
                loaderValue.equals("modloader", ignoreCase = true) -> out.add("risugami's modloader")
                loaderValue.equals("flint", ignoreCase = true) -> out.add("flint loader")
                else -> out.add(loaderValue)
            }
        }
        return out
    }
}
