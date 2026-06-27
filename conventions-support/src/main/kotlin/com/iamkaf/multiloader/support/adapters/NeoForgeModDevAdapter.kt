package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.ClientRunEnvironmentPolicy
import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

object NeoForgeModDevAdapter {
    fun configureCommon(
        project: Project,
        neoFormVersion: String?,
        parchmentMinecraftVersion: String?,
        parchmentVersion: String?,
        accessTransformerFile: File,
    ) {
        val neoForge = project.extensions.getByName("neoForge")
        GroovyGradleDsl.set(neoForge, "neoFormVersion", neoFormVersion)

        if (accessTransformerFile.exists()) {
            val accessTransformers = GroovyGradleDsl.get(neoForge, "accessTransformers")
            if (accessTransformers != null) {
                GroovyGradleDsl.invoke(accessTransformers, "from", accessTransformerFile.absolutePath)
            }
        }

        if (parchmentVersion != null) {
            GroovyGradleDsl.invoke(
                neoForge,
                "parchment",
                GroovyGradleDsl.closure { parchment ->
                    GroovyGradleDsl.invoke(parchment, "setMinecraftVersion", parchmentMinecraftVersion)
                    GroovyGradleDsl.invoke(parchment, "setMappingsVersion", parchmentVersion)
                },
            )
        }
    }

    fun configure(
        project: Project,
        neoforgeVersion: String?,
        modId: String,
        accessTransformerFile: File,
        parchmentVersion: String?,
        parchmentMinecraftVersion: String,
    ) {
        val neoForge = project.extensions.getByName("neoForge")
        GroovyGradleDsl.set(neoForge, "version", neoforgeVersion)

        if (accessTransformerFile.exists()) {
            val accessTransformers = GroovyGradleDsl.get(neoForge, "accessTransformers")
            if (accessTransformers != null) {
                GroovyGradleDsl.invoke(accessTransformers, "from", accessTransformerFile.absolutePath)
            }
        }

        if (parchmentVersion != null) {
            GroovyGradleDsl.invoke(
                neoForge,
                "parchment",
                GroovyGradleDsl.closure { parchment ->
                    GroovyGradleDsl.invoke(parchment, "setMinecraftVersion", parchmentMinecraftVersion)
                    GroovyGradleDsl.invoke(parchment, "setMappingsVersion", parchmentVersion)
                },
            )
        }

        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        GroovyGradleDsl.invoke(
            neoForge,
            "runs",
            GroovyGradleDsl.closure { runs ->
                GroovyGradleDsl.invoke(
                    runs,
                    "configureEach",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.invoke(run, "systemProperty", "neoforge.enabledGameTestNamespaces", modId)
                        GroovyGradleDsl.set(run, "ideName", "NeoForge ${runName(run)} (${project.path})")
                    },
                )
                configureNamedRun(runs, "client") { run ->
                    GroovyGradleDsl.invoke(run, "client")
                    ClientRunEnvironmentPolicy.applyToGroovyClientRun(project, run)
                }
                configureNamedRun(runs, "data") { run -> GroovyGradleDsl.invoke(run, "client") }
                configureNamedRun(runs, "server") { run -> GroovyGradleDsl.invoke(run, "server") }
            },
        )

        GroovyGradleDsl.invoke(
            neoForge,
            "mods",
            GroovyGradleDsl.closure { mods ->
                val mod = runCatching { GroovyGradleDsl.invoke(mods, "maybeCreate", modId) }.getOrNull()
                    ?: runCatching { GroovyGradleDsl.invoke(mods, "create", modId) }.getOrNull()
                    ?: throw IllegalStateException("[NeoForge] Could not create mod source entry '$modId'")
                GroovyGradleDsl.invoke(mod, "sourceSet", mainSourceSet)
            },
        )
    }

    private fun runName(run: Any): String {
        val name = runCatching { GroovyGradleDsl.get(run, "name")?.toString() }.getOrNull().orEmpty()
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun configureNamedRun(runs: Any, name: String, action: (Any) -> Unit = {}) {
        val run = runCatching { GroovyGradleDsl.invoke(runs, "maybeCreate", name) }.getOrNull()
            ?: runCatching { GroovyGradleDsl.invoke(runs, "create", name) }.getOrNull()
            ?: throw IllegalStateException("[NeoForge] Could not create run '$name'")
        action(run)
    }
}
