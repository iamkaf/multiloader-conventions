package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.ClientRunEnvironmentPolicy
import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

object ForgeGradleAdapter {
    fun configure(
        project: Project,
        minecraftVersion: String,
        mixinConfigs: List<String>,
        usesUnobfuscatedMinecraft: Boolean,
        accessTransformerFile: File,
        modId: String,
        forgeArtifactVersion: String? = null,
    ) {
        val minecraft = project.extensions.getByName("minecraft")
        if (!usesUnobfuscatedMinecraft) {
            GroovyGradleDsl.invoke(
                minecraft,
                "mappings",
                mapOf("channel" to "official", "version" to minecraftVersion),
            )
        }

        if (accessTransformerFile.exists()) {
            val accessTransformer = GroovyGradleDsl.get(minecraft, "accessTransformer")
            if (accessTransformer != null) {
                GroovyGradleDsl.invoke(accessTransformer, "from", accessTransformerFile)
            }
        }

        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        GroovyGradleDsl.invoke(
            minecraft,
            "runs",
            GroovyGradleDsl.closure { runs ->
                GroovyGradleDsl.invoke(
                    runs,
                    "configureEach",
                    GroovyGradleDsl.closure { run ->
                        val workingDir = GroovyGradleDsl.get(run, "workingDir")
                        if (workingDir != null) {
                            GroovyGradleDsl.invoke(workingDir, "convention", project.layout.projectDirectory.dir("run"))
                        }
                        mixinConfigs.forEach { mixinConfig ->
                            GroovyGradleDsl.invoke(run, "args", "--mixin.config", mixinConfig)
                        }
                        GroovyGradleDsl.invoke(run, "environment", "MOD_CLASSES", "{source_roots}")
                    },
                )

                GroovyGradleDsl.invoke(
                    runs,
                    "register",
                    "client",
                    GroovyGradleDsl.closure { run ->
                        ClientRunEnvironmentPolicy.applyToGroovyClientRun(project, run)
                    },
                )
                GroovyGradleDsl.invoke(
                    runs,
                    "register",
                    "server",
                    GroovyGradleDsl.closure { run -> GroovyGradleDsl.invoke(run, "args", "--nogui") },
                )

                listOf("client", "server").forEach { runName ->
                    val runProvider = GroovyGradleDsl.invoke(runs, "named", runName)
                    if (runProvider != null) {
                        GroovyGradleDsl.invoke(
                            runProvider,
                            "configure",
                            GroovyGradleDsl.closure { run ->
                                GroovyGradleDsl.invoke(
                                    run,
                                    "mods",
                                    GroovyGradleDsl.closure { mods ->
                                        configureNamedMod(mods, modId, "source", mainSourceSet)
                                    },
                                )
                            },
                        )
                    }
                }
            },
        )

        configureRepositories(project, minecraft, forgeArtifactVersion)
    }

    fun dependency(project: Project, forgeCoordinate: String): Any {
        val minecraft = project.extensions.getByName("minecraft")
        return requireNotNull(GroovyGradleDsl.invoke(minecraft, "dependency", forgeCoordinate))
    }

    fun configureRepositories(
        project: Project,
        minecraft: Any = project.extensions.getByName("minecraft"),
        forgeArtifactVersion: String? = null,
    ) {
        ForgeMavenizerCacheGuard.deleteEmptyForgeArtifacts(project, forgeArtifactVersion)
        GroovyGradleDsl.invoke(minecraft, "mavenizer", project.repositories)

        val fg = project.extensions.findByName("fg") ?: return
        if (hasGroovyProperty(fg, "forgeMaven")) {
            GroovyGradleDsl.invoke(project.repositories, "maven", GroovyGradleDsl.get(fg, "forgeMaven"))
        }
        if (hasGroovyProperty(fg, "minecraftLibsMaven")) {
            GroovyGradleDsl.invoke(project.repositories, "maven", GroovyGradleDsl.get(fg, "minecraftLibsMaven"))
        }
    }

    fun configureNamedMod(mods: Any, modId: String, sourceMethod: String, mainSourceSet: Any) {
        val mod = runCatching { GroovyGradleDsl.invoke(mods, "maybeCreate", modId) }.getOrNull()
            ?: runCatching { GroovyGradleDsl.invoke(mods, "create", modId) }.getOrNull()
            ?: throw IllegalStateException("[Forge] Could not create mod source entry '$modId'")
        GroovyGradleDsl.invoke(mod, sourceMethod, mainSourceSet)
    }

    private fun hasGroovyProperty(target: Any, property: String): Boolean =
        (GroovyGradleDsl.invoke(target, "hasProperty", property) as? Boolean) == true

    fun configureOutputDirectories(project: Project) {
        project.extensions.getByType(SourceSetContainer::class.java).configureEach {
            val dir = project.layout.buildDirectory.dir("sourcesSets/$name")
            output.setResourcesDir(dir.get().asFile)
            java.destinationDirectory.set(dir)
        }
    }

    fun artifactVersion(minecraftVersion: String, forgeVersion: String): String =
        if (forgeVersion.startsWith("$minecraftVersion-")) forgeVersion else "$minecraftVersion-$forgeVersion"
}
