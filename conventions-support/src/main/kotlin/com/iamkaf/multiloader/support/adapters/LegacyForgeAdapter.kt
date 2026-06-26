package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

object LegacyForgeAdapter {
    fun configureCommon(
        project: Project,
        minecraftVersion: String?,
        parchmentMinecraftVersion: String?,
        parchmentVersion: String?,
        accessTransformerFile: File,
    ) {
        val legacyForge = project.extensions.getByName("legacyForge")
        GroovyGradleDsl.set(legacyForge, "mcpVersion", minecraftVersion)

        if (accessTransformerFile.exists()) {
            GroovyGradleDsl.set(legacyForge, "accessTransformers", listOf(accessTransformerFile.absolutePath))
        }

        if (parchmentVersion != null) {
            GroovyGradleDsl.invoke(
                legacyForge,
                "parchment",
                GroovyGradleDsl.closure { parchment ->
                    GroovyGradleDsl.invoke(parchment, "setMinecraftVersion", parchmentMinecraftVersion ?: minecraftVersion)
                    GroovyGradleDsl.invoke(parchment, "setMappingsVersion", parchmentVersion)
                },
            )
        }
    }

    fun configure(
        project: Project,
        minecraftVersion: String,
        forgeVersion: String,
        mixinConfigs: List<String>,
        modId: String,
        accessTransformerFile: File,
        usesUnobfuscatedMinecraft: Boolean,
    ) {
        val legacyForge = project.extensions.getByName("legacyForge")
        if (usesUnobfuscatedMinecraft) {
            GroovyGradleDsl.set(legacyForge, "mcpVersion", minecraftVersion)
        } else {
            GroovyGradleDsl.set(legacyForge, "version", ForgeGradleAdapter.artifactVersion(minecraftVersion, forgeVersion))
        }
        GroovyGradleDsl.set(legacyForge, "validateAccessTransformers", true)

        if (accessTransformerFile.exists()) {
            GroovyGradleDsl.set(legacyForge, "accessTransformers", listOf(accessTransformerFile.absolutePath))
        }

        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        GroovyGradleDsl.invoke(
            legacyForge,
            "runs",
            GroovyGradleDsl.closure { runs ->
                GroovyGradleDsl.invoke(
                    runs,
                    "configureEach",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.set(run, "gameDirectory", project.file("run"))
                        mixinConfigs.forEach { mixinConfig ->
                            GroovyGradleDsl.invoke(run, "programArgument", "--mixin.config")
                            GroovyGradleDsl.invoke(run, "programArgument", mixinConfig)
                        }
                    },
                )
                configureNamedRun(runs, "client") { run -> GroovyGradleDsl.invoke(run, "client") }
                configureNamedRun(runs, "server") { run ->
                    GroovyGradleDsl.invoke(run, "server")
                    GroovyGradleDsl.invoke(run, "programArgument", "--nogui")
                }
            },
        )

        GroovyGradleDsl.invoke(
            legacyForge,
            "mods",
            GroovyGradleDsl.closure { mods ->
                ForgeGradleAdapter.configureNamedMod(mods, modId, "sourceSet", mainSourceSet)
            },
        )
    }

    private fun configureNamedRun(runs: Any, name: String, action: (Any) -> Unit = {}) {
        val run = runCatching { GroovyGradleDsl.invoke(runs, "maybeCreate", name) }.getOrNull()
            ?: runCatching { GroovyGradleDsl.invoke(runs, "create", name) }.getOrNull()
            ?: throw IllegalStateException("[LegacyForge] Could not create run '$name'")
        action(run)
    }
}
