package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.ClientRunEnvironmentPolicy
import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
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
                        if (minecraftVersion == "1.16.5") {
                            configureLegacyForge1165ClientRun(project, run, forgeArtifactVersion)
                        }
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
        if (minecraftVersion == "1.16.5") {
            configureLegacyForge1165RunTask(project, forgeArtifactVersion)
        }
    }

    private fun configureLegacyForge1165ClientRun(project: Project, run: Any, forgeArtifactVersion: String?) {
        val assetsDir = legacyForge1165AssetsDir(project)
        val nativesDir = legacyForge1165NativesDir(project, forgeArtifactVersion)
        GroovyGradleDsl.invoke(run, "environment", "assetIndex", "1.16")
        GroovyGradleDsl.invoke(run, "environment", "assetDirectory", assetsDir.absolutePath)
        GroovyGradleDsl.invoke(run, "environment", "nativesDirectory", nativesDir.absolutePath)
        GroovyGradleDsl.invoke(run, "systemProperty", "java.library.path", nativesDir.absolutePath)
        GroovyGradleDsl.invoke(run, "systemProperty", "org.lwjgl.librarypath", nativesDir.absolutePath)
    }

    private fun configureLegacyForge1165RunTask(project: Project, forgeArtifactVersion: String?) {
        val assetsDir = legacyForge1165AssetsDir(project)
        val nativesDir = legacyForge1165NativesDir(project, forgeArtifactVersion)
        val prepareEnvironment = project.tasks.register("prepareLegacyForge1165ClientEnvironment") {
            group = "minecraft"
            description = "Prepares directories required by the legacy Forge 1.16.5 client run."
            outputs.dir(assetsDir)
            outputs.dir(nativesDir)
            doLast {
                assetsDir.mkdirs()
                nativesDir.mkdirs()
            }
        }
        val launcher = project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(16))
        }
        project.tasks.withType(JavaExec::class.java).matching { it.name == "runClient" }.configureEach {
            dependsOn(prepareEnvironment)
            javaLauncher.set(launcher)
            environment("assetIndex", "1.16")
            environment("assetDirectory", assetsDir.absolutePath)
            environment("nativesDirectory", nativesDir.absolutePath)
            systemProperty("java.library.path", nativesDir.absolutePath)
            systemProperty("org.lwjgl.librarypath", nativesDir.absolutePath)
        }
    }

    private fun legacyForge1165AssetsDir(project: Project): File =
        File(project.gradle.gradleUserHomeDir, "caches/fabric-loom/assets")

    private fun legacyForge1165NativesDir(project: Project, forgeArtifactVersion: String?): File =
        File(
            project.gradle.gradleUserHomeDir,
            "caches/minecraftforge/forgegradle/slime-launcher/cache/net/minecraftforge/forge/" +
                "${forgeArtifactVersion ?: "1.16.5"}/natives",
        )

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
