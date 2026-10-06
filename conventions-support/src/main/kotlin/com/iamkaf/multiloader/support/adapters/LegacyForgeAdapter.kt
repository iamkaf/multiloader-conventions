package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.ClientRunEnvironmentPolicy
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
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
        AmberToolchain.useAmberNeoFormRuntime(project)
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
        mixinVersion: String,
    ) {
        val legacyForge = project.extensions.getByName("legacyForge")
        AmberToolchain.useAmberNeoFormRuntime(project)
        if (usesUnobfuscatedMinecraft) {
            GroovyGradleDsl.set(legacyForge, "mcpVersion", minecraftVersion)
        } else {
            GroovyGradleDsl.set(legacyForge, "version", ForgeGradleAdapter.artifactVersion(minecraftVersion, forgeVersion))
        }
        GroovyGradleDsl.set(legacyForge, "validateAccessTransformers", true)

        if (accessTransformerFile.exists()) {
            GroovyGradleDsl.set(legacyForge, "accessTransformers", listOf(accessTransformerFile.absolutePath))
        }

        if (VersionPolicy.isMinecraftVersionAtLeast(minecraftVersion, "1.17") &&
            !VersionPolicy.isMinecraftVersionAtLeast(minecraftVersion, "1.18")
        ) {
            // Forge 37's Mixin 0.8.4 declares "requires static gson", the automatic module name of Gson 2.8.0.
            // Newer Gson is named com.google.gson, so the run classpath must keep 2.8.0 or Mixin fails to load.
            project.configurations.matching { it.name.endsWith("LegacyClasspath") }.configureEach {
                resolutionStrategy.force("com.google.code.gson:gson:2.8.0")
            }
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
                configureNamedRun(runs, "client") { run ->
                    GroovyGradleDsl.invoke(run, "client")
                    ClientRunEnvironmentPolicy.applyToGroovyClientRun(project, run)
                }
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

        if (!usesUnobfuscatedMinecraft) {
            configureMixinRefmap(project, mainSourceSet, mixinConfigs, modId, mixinVersion)
        }
    }

    /**
     * Production Forge on these lines runs SRG member names, so Mojang names in mixin annotations
     * resolve only through a refmap. ModDevGradle's mixin extension runs the Mixin annotation
     * processor against its official-to-SRG mappings, packs the refmap into the jar, and feeds the
     * processor's mappings to reobfJar for shadowed members. Mixin reads the refmap from each config.
     */
    private fun configureMixinRefmap(
        project: Project,
        mainSourceSet: SourceSet,
        mixinConfigs: List<String>,
        modId: String,
        mixinVersion: String,
    ) {
        if (mixinConfigs.isEmpty()) return
        val refmap = "$modId.refmap.json"
        project.dependencies.add("annotationProcessor", "org.spongepowered:mixin:$mixinVersion:processor")
        GroovyGradleDsl.invoke(project.extensions.getByName("mixin"), "add", mainSourceSet, refmap)
        project.tasks.named(mainSourceSet.compileJavaTaskName, JavaCompile::class.java) {
            // Loader-added targets, such as Forge's onSheared, have no SRG mapping and keep their name at
            // runtime. Report them like unmapped shadows and accessors instead of failing the build.
            options.compilerArgs.add("-AMSG_NO_OBFDATA_FOR_TARGET=warning")
        }

        MixinRefmapInjection.nameRefmapInConfigs(project, mixinConfigs, refmap)
    }

    private fun configureNamedRun(runs: Any, name: String, action: (Any) -> Unit = {}) {
        val run = runCatching { GroovyGradleDsl.invoke(runs, "maybeCreate", name) }.getOrNull()
            ?: runCatching { GroovyGradleDsl.invoke(runs, "create", name) }.getOrNull()
            ?: throw IllegalStateException("[LegacyForge] Could not create run '$name'")
        action(run)
    }
}

object MixinRefmapInjection {
    private val refmapKey = Regex("\"refmap\"\\s*:")

    /** Names [refmap] in each mixin config that does not already name one. */
    fun nameRefmapInConfigs(project: Project, mixinConfigs: List<String>, refmap: String) {
        project.tasks.named("processResources", ProcessResources::class.java) {
            inputs.property("mixinRefmap", refmap)
            filesMatching(mixinConfigs) {
                if (!declaresRefmap(file.readText())) {
                    var injected = false
                    filter { line ->
                        if (injected) return@filter line
                        val updated = injectIntoLine(line, refmap) ?: return@filter line
                        injected = true
                        updated
                    }
                }
            }
        }
    }

    fun declaresRefmap(config: String): Boolean = refmapKey.containsMatchIn(config)

    /** Returns [line] with the refmap key added after its opening brace, or null if it has none. */
    fun injectIntoLine(line: String, refmap: String): String? {
        val brace = line.indexOf('{')
        if (brace < 0) return null
        return line.substring(0, brace + 1) + "\n  \"refmap\": \"$refmap\"," + line.substring(brace + 1)
    }
}
