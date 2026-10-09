package com.iamkaf.multiloader.root

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Relocated merges give each loader its own copy of a clashing class, suffixed `_<loader>`. Forgix renames the
 * entries in each loader's mixin config to match, except entries in a subpackage such as `client.ScreenMixin`, which
 * then load another loader's copy. These helpers find and repair those entries.
 */
object HorizontalMixinConfigs {
    private val mixinKeys = listOf("mixins", "client", "server")

    /** The loader's own copy of [className] when the merge relocated it and [className] is someone else's. */
    fun loaderCopy(classPaths: Set<String>, loader: String, className: String): String? {
        val suffix = "_$loader"
        if (className.endsWith(suffix)) return null
        return (className + suffix).takeIf { classPath(it) in classPaths }
    }

    fun classPath(className: String): String = className.trim().replace('.', '/') + ".class"

    fun qualify(mixinPackage: String, relativeName: String): String =
        when {
            mixinPackage.isBlank() -> relativeName
            relativeName.startsWith("$mixinPackage.") -> relativeName
            else -> "$mixinPackage.$relativeName"
        }

    /** Points every `*_<loader>.json` mixin config entry at that loader's copy. Returns the rewritten entries. */
    fun repair(jar: File, loaders: List<String>): List<String> {
        val rewrites = mutableListOf<String>()
        val replaced = linkedMapOf<String, ByteArray>()
        ZipFile(jar).use { zip ->
            val classPaths = zip.entries().asSequence().map(ZipEntry::getName).filter { it.endsWith(".class") }.toSet()
            zip.entries().asSequence()
                .filter { !it.isDirectory && !it.name.contains('/') && it.name.endsWith(".json") }
                .forEach { entry ->
                    val loader = loaders.firstOrNull { entry.name.endsWith("_$it.json") } ?: return@forEach
                    val parsed = zip.getInputStream(entry).use { JsonSlurper().parse(it) } as? Map<*, *>
                        ?: return@forEach
                    val config = parsed.entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
                    val mixinPackage = config["package"]?.toString()?.trim() ?: return@forEach
                    var changed = false
                    mixinKeys.forEach { key ->
                        val mixins = config[key] as? List<*> ?: return@forEach
                        config[key] = mixins.map { value ->
                            val name = value?.toString() ?: return@map value
                            if (loaderCopy(classPaths, loader, qualify(mixinPackage, name)) == null) return@map value
                            changed = true
                            rewrites += "${entry.name}: $name -> ${name}_$loader"
                            "${name}_$loader"
                        }
                    }
                    if (changed) {
                        replaced[entry.name] = JsonOutput.prettyPrint(JsonOutput.toJson(config))
                            .toByteArray(StandardCharsets.UTF_8)
                    }
                }
        }
        if (replaced.isNotEmpty()) rewrite(jar, replaced)
        return rewrites
    }

    private fun rewrite(jar: File, replaced: Map<String, ByteArray>) {
        val temporary = File(jar.parentFile, "${jar.name}.repairing")
        ZipFile(jar).use { zip ->
            ZipOutputStream(temporary.outputStream()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    val bytes = replaced[entry.name]
                    if (bytes != null) out.write(bytes) else zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
