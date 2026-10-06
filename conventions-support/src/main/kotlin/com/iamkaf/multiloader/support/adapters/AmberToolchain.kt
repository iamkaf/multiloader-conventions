package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.BuildToolsVersions
import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.file.ConfigurableFileCollection

/**
 * Amber forks of the loader toolchains. They share or hard-link cached Minecraft jars instead of copying them into
 * every project, which costs several gigabytes per mod across the version matrix.
 */
object AmberToolchain {
    /** Amber Loom for unobfuscated Minecraft. */
    const val LOOM = "com.iamkaf.amber.loom"

    /** Amber Loom for obfuscated Minecraft, which remaps mods. */
    const val LOOM_REMAP = "com.iamkaf.amber.loom-remap"

    /** Call before ForgeGradle reads the Mavenizer tool, which happens in `minecraft.dependency(...)`. */
    fun useAmberMavenizer(project: Project) {
        val tools = project.extensions.findByName("fgtools") ?: return
        val mavenizer = project.configurations.detachedConfiguration(
            project.dependencies.create(
                "com.iamkaf.amber.toolchain:amber-mavenizer:${BuildToolsVersions.required("amberMavenizer")}",
            ),
        ).apply { isTransitive = false }
        GroovyGradleDsl.invoke(
            tools,
            "configure",
            "mavenizer",
            GroovyGradleDsl.closure { definition ->
                GroovyGradleDsl.invoke(requireNotNull(GroovyGradleDsl.get(definition, "classpath")), "from", mavenizer)
            },
        )
    }

    /**
     * ModDevGradle hard-codes the NeoForm Runtime coordinates, so point its tasks at the Amber CLI jar directly.
     * Amber NeoForm Runtime is based on the version ModDevGradle expects, so its external tools still resolve upstream.
     */
    fun useAmberNeoFormRuntime(project: Project) {
        val neoFormRuntime = project.configurations.detachedConfiguration(
            project.dependencies.create(
                "com.iamkaf.amber.toolchain:amber-neoform-runtime:${BuildToolsVersions.required("amberNeoformRuntime")}",
            ),
        ).apply {
            isTransitive = false
            attributes {
                attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling::class.java, Bundling.SHADOWED))
            }
        }
        project.tasks.configureEach {
            if (hasProperty("neoFormRuntime")) {
                (GroovyGradleDsl.get(this, "neoFormRuntime") as ConfigurableFileCollection).setFrom(neoFormRuntime)
            }
        }
    }
}
