package com.iamkaf.multiloader.root

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

object HorizontalJarValidator {
    fun validate(
        mergedJar: File,
        sourceJars: Map<String, File>,
        modId: String,
        tier: HorizontalMergeTier,
    ) {
        val problems = mutableListOf<String>()
        val merged = readJar(mergedJar, problems)
        val sources = sourceJars.mapValues { (loader, file) -> readJar(file, problems, "$loader source") }

        requireLoaderMetadata(merged, sources.keys, problems)
        validateFabricMetadata(merged, sources["fabric"], modId, tier, "fabric" in sources, problems)
        validateTomlMetadata(
            merged,
            sources["forge"],
            "forge",
            "META-INF/mods.toml",
            modId,
            tier,
            "forge" in sources,
            problems,
        )
        validateTomlMetadata(
            merged,
            sources["neoforge"],
            "neoforge",
            "META-INF/neoforge.mods.toml",
            modId,
            tier,
            "neoforge" in sources,
            problems,
        )
        validateAccessFiles(merged, sources, problems)
        validateAssetsAndData(merged, sources, tier, problems)

        if (tier == HorizontalMergeTier.STABLE) {
            validateStableCommonPaths(merged, sources, problems)
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("Horizontal jar validation failed for ")
                    append(mergedJar)
                    append(':')
                    problems.forEach { problem -> append("\n - ").append(problem) }
                },
            )
        }
    }

    private fun requireLoaderMetadata(
        merged: Map<String, ByteArray>,
        loaders: Set<String>,
        problems: MutableList<String>,
    ) {
        val metadata = mapOf(
            "fabric" to "fabric.mod.json",
            "forge" to "META-INF/mods.toml",
            "neoforge" to "META-INF/neoforge.mods.toml",
        )
        loaders.forEach { loader ->
            val path = metadata[loader]
            if (path == null) {
                problems += "unknown loader '$loader'"
            } else if (path !in merged) {
                problems += "$loader metadata is missing: $path"
            }
        }
    }

    private fun validateFabricMetadata(
        merged: Map<String, ByteArray>,
        source: Map<String, ByteArray>?,
        modId: String,
        tier: HorizontalMergeTier,
        required: Boolean,
        problems: MutableList<String>,
    ) {
        if (!required) return
        if (source?.containsKey("fabric.mod.json") != true) {
            problems += "fabric source metadata is missing: fabric.mod.json"
        }
        val metadata = parseJsonObject(merged, "fabric.mod.json", problems) ?: return
        validateSourceModId(source, "fabric.mod.json", modId, problems)
        val expectedIds = expectedModIds(modId, "fabric", tier)
        if (metadata["id"]?.toString() !in expectedIds) {
            problems += "fabric.mod.json changed mod id '${metadata["id"]}' (expected ${expectedIds.joinToString(" or ")})"
        }

        val mergedMixins = fabricReferenceList(metadata["mixins"])
        val sourceMetadata = source?.let { parseJsonObject(it, "fabric.mod.json", problems, "fabric source ") }
        val sourceMixins = fabricReferenceList(sourceMetadata?.get("mixins"))
        if (mergedMixins.size < sourceMixins.size) {
            problems += "fabric.mod.json dropped mixin configs (source=${sourceMixins.size}, merged=${mergedMixins.size})"
        }
        mergedMixins.forEach { config ->
            requireEntry(merged, config, "Fabric mixin config", problems)
            validateMixinConfig(merged, config, problems)
        }

        val sourceAccessWidener = sourceMetadata?.get("accessWidener")?.toString()?.takeIf { it.isNotBlank() }
        val mergedAccessWidener = metadata["accessWidener"]?.toString()?.takeIf { it.isNotBlank() }
        if (sourceAccessWidener != null && mergedAccessWidener == null) {
            problems += "fabric.mod.json dropped its access widener reference: $sourceAccessWidener"
        }
        mergedAccessWidener?.let { accessWidener ->
            requireEntry(merged, accessWidener, "Fabric access widener", problems)
            if (tier == HorizontalMergeTier.STABLE && sourceAccessWidener != null) {
                compareEntryBytes(source.orEmpty(), sourceAccessWidener, merged, accessWidener, "Fabric access widener", problems)
            }
        }

        val entrypoints = metadata["entrypoints"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val sourceEntrypoints = sourceMetadata?.get("entrypoints") as? Map<*, *> ?: emptyMap<Any?, Any?>()
        sourceEntrypoints.forEach { (key, value) ->
            val sourceCount = entrypointValues(value).size
            val mergedCount = entrypointValues(entrypoints[key]).size
            if (mergedCount < sourceCount) {
                problems += "fabric.mod.json dropped '$key' entrypoints (source=$sourceCount, merged=$mergedCount)"
            }
        }
        entrypoints.values.flatMap(::entrypointValues).forEach { className ->
            requireClass(merged, className.substringBefore("::"), "Fabric entrypoint", problems)
        }
    }

    private fun validateTomlMetadata(
        merged: Map<String, ByteArray>,
        source: Map<String, ByteArray>?,
        loader: String,
        path: String,
        modId: String,
        tier: HorizontalMergeTier,
        required: Boolean,
        problems: MutableList<String>,
    ) {
        if (!required) return
        if (source?.containsKey(path) != true) problems += "$loader source metadata is missing: $path"
        val bytes = merged[path] ?: return
        val text = bytes.toString(StandardCharsets.UTF_8)
        val ids = tomlBlockValues(text, "mods", "modId")
        validateSourceTomlModId(source, path, modId, problems)
        val expectedIds = expectedModIds(modId, loader, tier)
        if (ids.isEmpty()) {
            problems += "$path has no [[mods]] modId"
        } else if (ids.none { it in expectedIds }) {
            problems += "$path changed $loader mod id to ${ids.joinToString()} (expected ${expectedIds.joinToString(" or ")})"
        }

        val mergedMixins = tomlBlockValues(text, "mixins", "config")
        val sourceMixins = source?.get(path)
            ?.toString(StandardCharsets.UTF_8)
            ?.let { tomlBlockValues(it, "mixins", "config") }
            .orEmpty()
        if (mergedMixins.size < sourceMixins.size) {
            problems += "$path dropped mixin configs (source=${sourceMixins.size}, merged=${mergedMixins.size})"
        }
        mergedMixins.forEach { config ->
            requireEntry(merged, config, "$loader mixin config", problems)
            validateMixinConfig(merged, config, problems)
        }
    }

    private fun validateMixinConfig(
        merged: Map<String, ByteArray>,
        configPath: String,
        problems: MutableList<String>,
    ) {
        val config = parseJsonObject(merged, configPath, problems) ?: return
        val mixinPackage = config["package"]?.toString()?.trim().orEmpty()
        listOf("mixins", "client", "server").forEach { key ->
            val mixins = config[key] as? Collection<*> ?: return@forEach
            mixins.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.forEach { relativeName ->
                val className = when {
                    mixinPackage.isBlank() -> relativeName
                    relativeName.startsWith("$mixinPackage.") -> relativeName
                    else -> "$mixinPackage.$relativeName"
                }
                requireClass(merged, className, "Mixin from $configPath", problems)
            }
        }
        config["plugin"]?.toString()?.takeIf { it.isNotBlank() }?.let { pluginClass ->
            requireClass(merged, pluginClass, "Mixin plugin from $configPath", problems)
        }
    }

    private fun validateAccessFiles(
        merged: Map<String, ByteArray>,
        sources: Map<String, Map<String, ByteArray>>,
        problems: MutableList<String>,
    ) {
        val path = "META-INF/accesstransformer.cfg"
        val accessTransformers = listOf("forge", "neoforge").mapNotNull { loader ->
            sources[loader]?.get(path)?.let { loader to it }
        }
        if (accessTransformers.isNotEmpty()) {
            val actual = merged[path]
            if (actual == null) {
                problems += "Forge-like access transformer is missing: $path"
            } else {
                val transformerBytes = accessTransformers.map(Pair<String, ByteArray>::second)
                val transformersDiffer = transformerBytes.any { bytes -> !bytes.contentEquals(transformerBytes.first()) }
                if (transformersDiffer) {
                    problems += "Forge/NeoForge access transformers differ and cannot share $path"
                }
                if (accessTransformers.none { (_, bytes) -> actual.contentEquals(bytes) }) {
                    problems += "Forge-like access transformer changed bytes: $path"
                }
            }
        }
    }

    private fun validateAssetsAndData(
        merged: Map<String, ByteArray>,
        sources: Map<String, Map<String, ByteArray>>,
        tier: HorizontalMergeTier,
        problems: MutableList<String>,
    ) {
        val paths = sources.values
            .flatMap { it.keys }
            .filter { it.startsWith("assets/") || it.startsWith("data/") }
            .toSortedSet()

        paths.forEach { path ->
            val sourceEntries = sources.mapNotNull { (loader, entries) -> entries[path]?.let { loader to it } }
            val sourceBytes = sourceEntries.map(Pair<String, ByteArray>::second)
            val expected = sourceBytes.first()
            if (tier == HorizontalMergeTier.STABLE && sourceBytes.any { !it.contentEquals(expected) }) {
                problems += "asset/data collision has different loader bytes: $path"
                return@forEach
            }

            if (tier == HorizontalMergeTier.STABLE) {
                val actual = merged[path]
                when {
                    actual == null -> problems += "asset/data entry is missing: $path"
                    !actual.contentEquals(expected) -> problems += "asset/data entry changed bytes: $path"
                }
            } else {
                sourceEntries.forEach { (loader, bytes) ->
                    val candidates = listOfNotNull(path, loaderSuffix(path, loader))
                    if (candidates.none { candidate -> merged[candidate]?.contentEquals(bytes) == true }) {
                        problems += "unsafe-tier $loader asset/data entry is missing or changed: $path"
                    }
                }
            }
        }
    }

    private fun validateSourceModId(
        source: Map<String, ByteArray>?,
        path: String,
        modId: String,
        problems: MutableList<String>,
    ) {
        val metadata = source?.let { parseJsonObject(it, path, problems, "source ") } ?: return
        if (metadata["id"]?.toString() != modId) {
            problems += "source $path has mod id '${metadata["id"]}' (expected '$modId')"
        }
    }

    private fun validateSourceTomlModId(
        source: Map<String, ByteArray>?,
        path: String,
        modId: String,
        problems: MutableList<String>,
    ) {
        val text = source?.get(path)?.toString(StandardCharsets.UTF_8) ?: return
        val ids = tomlBlockValues(text, "mods", "modId")
        if (modId !in ids) problems += "source $path has mod ids ${ids.joinToString()} (expected '$modId')"
    }

    private fun expectedModIds(modId: String, loader: String, tier: HorizontalMergeTier): Set<String> =
        if (tier == HorizontalMergeTier.UNSTABLE_RELOCATED && loader in setOf("fabric", "forge")) {
            linkedSetOf(modId, "${modId}_$loader")
        } else {
            setOf(modId)
        }

    private fun compareEntryBytes(
        source: Map<String, ByteArray>,
        sourcePath: String,
        merged: Map<String, ByteArray>,
        mergedPath: String,
        label: String,
        problems: MutableList<String>,
    ) {
        val expected = source[sourcePath] ?: return
        val actual = merged[mergedPath] ?: return
        if (!actual.contentEquals(expected)) problems += "$label changed bytes: $mergedPath"
    }

    private fun validateStableCommonPaths(
        merged: Map<String, ByteArray>,
        sources: Map<String, Map<String, ByteArray>>,
        problems: MutableList<String>,
    ) {
        if (sources.isEmpty()) return
        val commonPaths = sources.values
            .map { it.keys.toSet() }
            .reduce(Set<String>::intersect)
            .filter(::isStableCommonCandidate)
            .sorted()

        commonPaths.forEach { path ->
            val sourceBytes = sources.values.map { it.getValue(path) }
            val expected = sourceBytes.first()
            if (sourceBytes.any { !it.contentEquals(expected) }) {
                problems += "stable common entry differs across loader inputs: $path"
                return@forEach
            }

            val actual = merged[path]
            when {
                actual == null -> problems += "stable common entry was renamed or removed: $path"
                // Forgix runs classes through ASM and may deterministically reorder the
                // constant pool even when every loader input is byte-identical. Stable-tier
                // compatibility is the binary path/name contract; non-class resources must
                // still survive byte-for-byte.
                !path.endsWith(".class") && !actual.contentEquals(expected) ->
                    problems += "stable common entry changed bytes: $path"
            }

            sources.keys.forEach { loader ->
                loaderSuffix(path, loader)?.let { suffixedPath ->
                    if (suffixedPath in merged) {
                        problems += "stable common entry gained a loader-suffixed copy: $suffixedPath"
                    }
                }
            }
        }
    }

    private fun isStableCommonCandidate(path: String): Boolean =
        path != "META-INF/MANIFEST.MF" &&
            path != "fabric.mod.json" &&
            path != "META-INF/mods.toml" &&
            path != "META-INF/neoforge.mods.toml" &&
            !path.startsWith("META-INF/forgix/") &&
            !path.endsWith(".SF", ignoreCase = true) &&
            !path.endsWith(".RSA", ignoreCase = true) &&
            !path.endsWith(".DSA", ignoreCase = true)

    private fun loaderSuffix(path: String, loader: String): String? {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        if (dot <= slash) return null
        return path.substring(0, dot) + "_$loader" + path.substring(dot)
    }

    private fun parseJsonObject(
        entries: Map<String, ByteArray>,
        path: String,
        problems: MutableList<String>,
        labelPrefix: String = "",
    ): Map<*, *>? {
        val bytes = entries[path] ?: return null
        return try {
            JsonSlurper().parseText(bytes.toString(StandardCharsets.UTF_8)) as? Map<*, *>
                ?: run {
                    problems += "$labelPrefix$path is not a JSON object"
                    null
                }
        } catch (error: Exception) {
            problems += "$labelPrefix$path is invalid JSON: ${error.message}"
            null
        }
    }

    private fun fabricReferenceList(value: Any?): List<String> =
        when (value) {
            is Collection<*> -> value.mapNotNull(::fabricReference)
            else -> listOfNotNull(fabricReference(value))
        }

    private fun fabricReference(value: Any?): String? =
        when (value) {
            is String -> value.takeIf(String::isNotBlank)
            is Map<*, *> -> value["config"]?.toString()?.takeIf(String::isNotBlank)
            else -> null
        }

    private fun entrypointValues(value: Any?): List<String> =
        when (value) {
            is Collection<*> -> value.flatMap(::entrypointValues)
            is String -> listOf(value)
            is Map<*, *> -> value["value"]?.toString()?.let(::listOf).orEmpty()
            else -> emptyList()
        }

    private fun tomlBlockValues(text: String, block: String, key: String): List<String> {
        val blockPattern = Regex(
            """(?ms)^\s*\[\[${Regex.escape(block)}]]\s*(.*?)(?=^\s*\[\[|\z)""",
        )
        val keyPattern = Regex(
            """(?m)^\s*${Regex.escape(key)}\s*=\s*[\"']([^\"']+)[\"']\s*$""",
        )
        return blockPattern.findAll(text).mapNotNull { match ->
            keyPattern.find(match.groupValues[1])?.groupValues?.get(1)
        }.toList()
    }

    private fun requireEntry(
        entries: Map<String, ByteArray>,
        path: String,
        label: String,
        problems: MutableList<String>,
    ) {
        if (path !in entries) problems += "$label references a missing entry: $path"
    }

    private fun requireClass(
        entries: Map<String, ByteArray>,
        className: String,
        label: String,
        problems: MutableList<String>,
    ) {
        val path = className.trim().replace('.', '/') + ".class"
        if (path !in entries) problems += "$label references a missing class: $className ($path)"
    }

    private fun readJar(
        file: File,
        problems: MutableList<String>,
        label: String = "merged",
    ): Map<String, ByteArray> {
        if (!file.isFile) {
            problems += "$label jar does not exist: $file"
            return emptyMap()
        }
        return try {
            ZipFile(file).use { zip ->
                val entries = linkedMapOf<String, ByteArray>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    if (entry.isDirectory) continue
                    if (entry.name in entries) {
                        problems += "$label jar contains duplicate entry: ${entry.name}"
                    }
                    entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                }
                entries
            }
        } catch (error: Exception) {
            problems += "$label jar is not a readable ZIP: ${error.message}"
            emptyMap()
        }
    }
}
