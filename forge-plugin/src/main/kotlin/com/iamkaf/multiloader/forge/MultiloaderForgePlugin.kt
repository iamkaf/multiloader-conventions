package com.iamkaf.multiloader.forge

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

class MultiloaderForgePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!isStonecutterForgeProject(project)) {
            applyFlatForgePlugin(project)
            return
        }

        applyStonecutterForgePlugin(project)
    }

    private fun applyFlatForgePlugin(project: Project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin::class.java)

        val minecraftVersion = ConventionSupport.versionAlias(project, "minecraft")
        val useLegacyForgePlugin = VersionPolicy.usesLegacyForgePlugin(minecraftVersion)
        project.pluginManager.apply(if (useLegacyForgePlugin) "net.neoforged.moddev.legacyforge" else "net.minecraftforge.gradle")
        requireSupportedForgeVersion(project, minecraftVersion)

        val usesUnobfuscatedMinecraft = ConventionSupport.isUnobfuscatedMinecraft(project)
        val mixinConfigs = ConventionSupport.collectMixinConfigs(project, "forge")
        val modId = ConventionSupport.requiredProperty(project, "mod.id")

        project.tasks.named("jar", Jar::class.java) {
            if (mixinConfigs.isNotEmpty()) {
                manifest.attributes(mapOf("MixinConfigs" to mixinConfigs.joinToString(",")))
            }
        }

        val accessTransformerFile = project.file("src/main/resources/META-INF/accesstransformer.cfg")
        if (useLegacyForgePlugin) {
            configureFlatLegacyForge(project, minecraftVersion, mixinConfigs, usesUnobfuscatedMinecraft, accessTransformerFile, modId)
        } else {
            configureForgeGradle(project, minecraftVersion, mixinConfigs, usesUnobfuscatedMinecraft, accessTransformerFile, modId)
        }

        configureForgeOutputDirectories(project)
    }

    private fun applyStonecutterForgePlugin(project: Project) {
        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val context = MultiloaderProjectContext.of(project)
        val minecraftVersion = context.requiredProperty("project.minecraft")
        if (isGraphOnlyBuild(project)) {
            configureStonecutterForgeGraphOnly(project, context, minecraftVersion)
            return
        }

        val useLegacyForgePlugin = VersionPolicy.usesLegacyForgePlugin(minecraftVersion)
        project.pluginManager.apply(if (useLegacyForgePlugin) "net.neoforged.moddev.legacyforge" else "net.minecraftforge.gradle")
        requireSupportedForgeVersion(project, minecraftVersion)

        val catalog = context.catalogFor(minecraftVersion)
        val useUnobfuscatedMinecraft = context.useUnobfuscatedMinecraft(minecraftVersion)
        val modId = context.requiredProperty("mod.id")
        val modName = context.requiredProperty("mod.name")
        val loader = context.requiredProperty("loader")
        val teaKitVersion = context.versionOrNull(catalog, "teakit")
        val teaKitLibrary = catalog.findLibrary("teakit-forge")
        val hasTeaKit = teaKitLibrary.isPresent && teaKitVersion != null && teaKitVersion != "null"
        val useTeaKit = project.providers.systemProperty("$modId.withTeaKit")
            .orElse(project.providers.gradleProperty("$modId.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get() && modId != "teakit"
        val useLegacyTeaKitRunHack = minecraftVersion in setOf("1.16.5", "1.17.1", "1.18", "1.18.1", "1.18.2")
        val accessTransformerFile = project.rootProject.file("common/src/main/resources/META-INF/accesstransformer.cfg")
        val mixinConfigs = context.mixinConfigs(loader)
        val commonProject = project.project(":common:$minecraftVersion")

        project.group = context.requiredProperty("project.group")
        project.version = context.requiredProperty("project.version")

        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("$modId-$loader")
        }

        context.sharedRepositories()
        StonecutterSourceLayout.configureLoader(project, "forge", minecraftVersion, commonProject)

        configureJava(project, context)

        if (useLegacyForgePlugin) {
            configureStonecutterLegacyForge(
                project = project,
                context = context,
                catalog = catalog,
                minecraftVersion = minecraftVersion,
                mixinConfigs = mixinConfigs,
                modId = modId,
                accessTransformerFile = accessTransformerFile,
                useTeaKit = useTeaKit && !useLegacyTeaKitRunHack,
                hasTeaKit = hasTeaKit,
                teaKitLibrary = if (hasTeaKit) teaKitLibrary.get() else null,
            )
        } else {
            configureForgeGradle(
                project = project,
                minecraftVersion = minecraftVersion,
                mixinConfigs = mixinConfigs,
                usesUnobfuscatedMinecraft = useUnobfuscatedMinecraft,
                accessTransformerFile = accessTransformerFile,
                modId = modId,
                configureDependencies = false,
            )
        }

        project.dependencies.add("compileOnly", context.library(catalog, "mixin"))
        project.dependencies.add("compileOnly", context.library(catalog, "mixin-extras"))
        project.dependencies.add("compileOnly", "org.jetbrains:annotations:24.1.0")
        project.dependencies.add("annotationProcessor", context.library(catalog, "mixin-extras"))
        project.dependencies.add("implementation", context.library(catalog, "gson"))

        if (!useLegacyForgePlugin) {
            val forgeVersion = context.versionOrNull(catalog, "forge")
                ?: throw GradleException("Missing Forge version for ${project.path}")
            val forgeCoordinate = if (useUnobfuscatedMinecraft) {
                "net.minecraftforge:forge:$forgeVersion"
            } else {
                "net.minecraftforge:forge:${forgeArtifactVersion(minecraftVersion, forgeVersion)}"
            }
            val minecraft = project.extensions.getByName("minecraft")
            project.dependencies.add("implementation", requireNotNull(GroovyGradleDsl.invoke(minecraft, "dependency", forgeCoordinate)))
        }

        addStrictJopt(project)

        if (useTeaKit && hasTeaKit && !useLegacyTeaKitRunHack) {
            project.dependencies.add("runtimeOnly", teaKitLibrary.get())
        }

        configureForgeOutputDirectories(project)
        configureResources(project, context, minecraftVersion, loader, catalog)
        configureJarManifest(project, context, modName, loader, minecraftVersion, mixinConfigs)
        configurePublishing(project, context)
    }

    private fun configureStonecutterForgeGraphOnly(
        project: Project,
        context: MultiloaderProjectContext,
        minecraftVersion: String,
    ) {
        val modId = context.requiredProperty("mod.id")
        val loader = context.requiredProperty("loader")

        project.group = context.requiredProperty("project.group")
        project.version = context.requiredProperty("project.version")

        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("$modId-$loader")
        }

        StonecutterSourceLayout.configureGraphOnly(project)
        configureJava(project, context)

        project.tasks.withType(Jar::class.java).configureEach {
            exclude(".cache/**")
        }

        project.tasks.register("runClient") {
            group = "minecraft"
            description = "Placeholder Forge client run task for graph-only $minecraftVersion builds."
        }

        configurePublishing(project, context)
    }

    private fun configureJava(project: Project, context: MultiloaderProjectContext) {
        project.extensions.configure(JavaPluginExtension::class.java) {
            withSourcesJar()
            withJavadocJar()
            toolchain.languageVersion.set(
                JavaLanguageVersion.of(context.requiredProperty("project.java").toInt()),
            )
        }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
        }
        ConventionSupport.configureJavadoc(project)

        project.tasks.withType(ProcessResources::class.java).configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude(".cache/**")
        }

        project.tasks.named("sourcesJar", Jar::class.java) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude(".cache/**")
        }
    }

    private fun configureForgeGradle(
        project: Project,
        minecraftVersion: String,
        mixinConfigs: List<String>,
        usesUnobfuscatedMinecraft: Boolean,
        accessTransformerFile: File,
        modId: String,
        configureDependencies: Boolean = true,
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

                GroovyGradleDsl.invoke(runs, "register", "client", GroovyGradleDsl.closure { })
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

        configureForgeGradleRepositories(project, minecraft)

        if (configureDependencies) {
            if (!usesUnobfuscatedMinecraft) {
                val targetMinecraftVersion = ConventionSupport.versionAlias(project, "minecraft")
                val forgeVersion = ConventionSupport.versionAlias(project, "forge")
                val forgeCoordinate = "net.minecraftforge:forge:${forgeArtifactVersion(targetMinecraftVersion, forgeVersion)}"
                project.dependencies.add("implementation", requireNotNull(GroovyGradleDsl.invoke(minecraft, "dependency", forgeCoordinate)))
            } else {
                val forgeCoordinate = "net.minecraftforge:forge:${ConventionSupport.versionAlias(project, "forge")}"
                project.dependencies.add("implementation", requireNotNull(GroovyGradleDsl.invoke(minecraft, "dependency", forgeCoordinate)))
            }
            addStrictJopt(project)
        }
    }

    private fun configureForgeGradleRepositories(project: Project, minecraft: Any) {
        GroovyGradleDsl.invoke(minecraft, "mavenizer", project.repositories)

        val fg = project.extensions.findByName("fg") ?: return
        if (hasGroovyProperty(fg, "forgeMaven")) {
            GroovyGradleDsl.invoke(project.repositories, "maven", GroovyGradleDsl.get(fg, "forgeMaven"))
        }
        if (hasGroovyProperty(fg, "minecraftLibsMaven")) {
            GroovyGradleDsl.invoke(project.repositories, "maven", GroovyGradleDsl.get(fg, "minecraftLibsMaven"))
        }
    }

    private fun configureFlatLegacyForge(
        project: Project,
        minecraftVersion: String,
        mixinConfigs: List<String>,
        usesUnobfuscatedMinecraft: Boolean,
        accessTransformerFile: File,
        modId: String,
    ) {
        configureLegacyForge(
            project = project,
            minecraftVersion = minecraftVersion,
            forgeVersion = ConventionSupport.versionAlias(project, "forge"),
            mixinConfigs = mixinConfigs,
            modId = modId,
            accessTransformerFile = accessTransformerFile,
            usesUnobfuscatedMinecraft = usesUnobfuscatedMinecraft,
        )

        addStrictJopt(project)
    }

    private fun configureStonecutterLegacyForge(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
        mixinConfigs: List<String>,
        modId: String,
        accessTransformerFile: File,
        useTeaKit: Boolean,
        hasTeaKit: Boolean,
        teaKitLibrary: Any?,
    ) {
        configureLegacyForge(
            project = project,
            minecraftVersion = minecraftVersion,
            forgeVersion = context.versionOrNull(catalog, "forge")
                ?: throw GradleException("Missing Forge version for ${project.path}"),
            mixinConfigs = mixinConfigs,
            modId = modId,
            accessTransformerFile = accessTransformerFile,
            usesUnobfuscatedMinecraft = false,
        )

        if (useTeaKit && hasTeaKit && teaKitLibrary != null) {
            project.dependencies.add("modRuntimeOnly", teaKitLibrary)
        }
    }

    private fun configureLegacyForge(
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
            GroovyGradleDsl.set(legacyForge, "version", forgeArtifactVersion(minecraftVersion, forgeVersion))
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
                GroovyGradleDsl.invoke(
                    runs,
                    "client",
                    GroovyGradleDsl.closure { run -> GroovyGradleDsl.invoke(run, "client") },
                )
                GroovyGradleDsl.invoke(
                    runs,
                    "server",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.invoke(run, "server")
                        GroovyGradleDsl.invoke(run, "programArgument", "--nogui")
                    },
                )
            },
        )

        GroovyGradleDsl.invoke(
            legacyForge,
            "mods",
            GroovyGradleDsl.closure { mods ->
                configureNamedMod(mods, modId, "sourceSet", mainSourceSet)
            },
        )
    }

    private fun configureNamedMod(mods: Any, modId: String, sourceMethod: String, mainSourceSet: Any) {
        val mod = runCatching { GroovyGradleDsl.invoke(mods, "maybeCreate", modId) }.getOrNull()
            ?: runCatching { GroovyGradleDsl.invoke(mods, "create", modId) }.getOrNull()
            ?: throw IllegalStateException("[Forge] Could not create mod source entry '$modId'")
        GroovyGradleDsl.invoke(mod, sourceMethod, mainSourceSet)
    }

    private fun configureForgeOutputDirectories(project: Project) {
        project.extensions.getByType(SourceSetContainer::class.java).configureEach {
            val dir = project.layout.buildDirectory.dir("sourcesSets/$name")
            output.setResourcesDir(dir.get().asFile)
            java.destinationDirectory.set(dir)
        }
    }

    private fun configureResources(
        project: Project,
        context: MultiloaderProjectContext,
        minecraftVersion: String,
        loader: String,
        catalog: VersionCatalog,
    ) {
        val expandProps = context.expandProperties(minecraftVersion, loader, catalog)
        val jsonExpandProps = expandProps.mapValues { (_, value) ->
            if (value is String) value.replace("\n", "\\n") else value
        }

        project.tasks.named("processResources", ProcessResources::class.java) {
            inputs.properties(expandProps.filterValues { it != null })
            filesMatching(listOf("META-INF/mods.toml")) {
                expand(expandProps)
            }
            filesMatching(listOf("pack.mcmeta", "*.mixins.json")) {
                expand(jsonExpandProps)
            }
        }
    }

    private fun configureJarManifest(
        project: Project,
        context: MultiloaderProjectContext,
        modName: String,
        loader: String,
        minecraftVersion: String,
        mixinConfigs: List<String>,
    ) {
        project.tasks.named("jar", Jar::class.java) {
            manifest.attributes(
                mapOf(
                    "Specification-Title" to modName,
                    "Specification-Vendor" to context.optionalProperty("mod.authors"),
                    "Specification-Version" to project.version.toString(),
                    "Implementation-Title" to loader,
                    "Implementation-Version" to project.version.toString(),
                    "Implementation-Vendor" to context.optionalProperty("mod.authors"),
                    "Built-On-Minecraft" to minecraftVersion,
                    "Built-By" to "multiloader-conventions",
                ),
            )
            if (mixinConfigs.isNotEmpty()) {
                manifest.attributes(mapOf("MixinConfigs" to mixinConfigs.joinToString(",")))
            }
        }
    }

    private fun configurePublishing(project: Project, context: MultiloaderProjectContext) {
        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                from(project.components.getByName("java"))
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
            }
            context.publishingRepositories(this, project.version.toString())
        }
    }

    private fun addStrictJopt(project: Project) {
        val dependency = project.dependencies.create("net.sf.jopt-simple:jopt-simple:5.0.4") as ExternalModuleDependency
        dependency.version { strictly("5.0.4") }
        project.dependencies.add("implementation", dependency)
    }

    private fun hasGroovyProperty(target: Any, property: String): Boolean =
        (GroovyGradleDsl.invoke(target, "hasProperty", property) as? Boolean) == true

    private fun forgeArtifactVersion(minecraftVersion: String, forgeVersion: String): String =
        if (forgeVersion.startsWith("$minecraftVersion-")) forgeVersion else "$minecraftVersion-$forgeVersion"

    private fun requireSupportedForgeVersion(project: Project, minecraftVersion: String?) {
        if (minecraftVersion == "1.16.5") return

        if (minecraftVersion == "1.14.4" ||
            minecraftVersion == "1.15" ||
            minecraftVersion?.startsWith("1.15.") == true ||
            minecraftVersion == "1.16" ||
            minecraftVersion?.startsWith("1.16.") == true
        ) {
            throw GradleException(
                "Forge convention support starts at Minecraft 1.17. Keep ${project.path} on a repo-local legacy setup for $minecraftVersion.",
            )
        }
    }

    private fun isGraphOnlyBuild(project: Project): Boolean {
        val taskNames = project.gradle.startParameter.taskNames
        return taskNames.isNotEmpty() && taskNames.all { taskName ->
            val requested = taskName.split(":").last()
            requested == "writeMultiloaderGraph" || requested == "printMultiloaderGraph"
        }
    }

    private fun isStonecutterForgeProject(project: Project): Boolean =
        project.parent?.name == "forge"
}
