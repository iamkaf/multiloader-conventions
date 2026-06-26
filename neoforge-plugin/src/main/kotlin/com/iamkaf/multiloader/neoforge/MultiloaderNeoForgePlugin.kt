package com.iamkaf.multiloader.neoforge

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.NeoForgeToolchainStrategy
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Plugin
import org.gradle.api.Project
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

class MultiloaderNeoForgePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!isStonecutterNeoForgeProject(project)) {
            applyFlatNeoForgePlugin(project)
            return
        }

        applyStonecutterNeoForgePlugin(project)
    }

    private fun applyFlatNeoForgePlugin(project: Project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin::class.java)
        project.pluginManager.apply("net.neoforged.moddev")

        configureNeoForgeModDev(
            project = project,
            neoForge = project.extensions.getByName("neoForge"),
            neoforgeVersion = ConventionSupport.versionAlias(project, "neoforge"),
            modId = ConventionSupport.requiredProperty(project, "mod.id"),
            accessTransformerFile = ConventionSupport.commonFile(project, "src/main/resources/META-INF/accesstransformer.cfg"),
            parchmentVersion = ConventionSupport.optionalVersionAlias(project, "parchment"),
            parchmentMinecraftVersion = ConventionSupport.optionalVersionAlias(project, "parchment-minecraft")
                ?: ConventionSupport.versionAlias(project, "minecraft"),
        )
    }

    private fun applyStonecutterNeoForgePlugin(project: Project) {
        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val context = MultiloaderProjectContext.of(project)
        val minecraftVersion = context.requiredProperty("project.minecraft")
        val useNeoGradleUserdev =
            VersionPolicy.neoForgeToolchainStrategy(minecraftVersion) == NeoForgeToolchainStrategy.NEOGRADLE_USERDEV
        project.pluginManager.apply(if (useNeoGradleUserdev) "net.neoforged.gradle.userdev" else "net.neoforged.moddev")

        val catalog = context.catalogFor(minecraftVersion)
        val hasParchment = context.versionOrNull(catalog, "parchment") != null
        val modId = context.requiredProperty("mod.id")
        val modName = context.requiredProperty("mod.name")
        val loader = context.requiredProperty("loader")
        val teaKitVersion = context.versionOrNull(catalog, "teakit")
        val teaKitLibrary = catalog.findLibrary("teakit-neoforge")
        val hasTeaKit = teaKitLibrary.isPresent && teaKitVersion != null && teaKitVersion != "null"
        val useTeaKit = project.providers.systemProperty("$modId.withTeaKit")
            .orElse(project.providers.gradleProperty("$modId.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get() && modId != "teakit"
        val accessTransformerFile = project.rootProject.file("common/src/main/resources/META-INF/accesstransformer.cfg")
        val commonProject = project.project(":common:$minecraftVersion")

        project.group = context.requiredProperty("project.group")
        project.version = context.requiredProperty("project.version")

        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("$modId-$loader")
        }

        context.sharedRepositories()
        StonecutterSourceLayout.configureLoader(project, "neoforge", minecraftVersion, commonProject)

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

        project.dependencies.add("compileOnly", context.library(catalog, "mixin"))
        project.dependencies.add("compileOnly", context.library(catalog, "mixin-extras"))
        project.dependencies.add("compileOnly", "org.jetbrains:annotations:24.1.0")
        project.dependencies.add("annotationProcessor", context.library(catalog, "mixin-extras"))
        project.dependencies.add("implementation", context.library(catalog, "gson"))
        if (useTeaKit && hasTeaKit) {
            project.dependencies.add("runtimeOnly", teaKitLibrary.get())
        }

        if (useNeoGradleUserdev) {
            configureNeoGradleUserdev(project, context, catalog, modId)
        } else {
            configureNeoForgeModDev(
                project = project,
                neoForge = project.extensions.getByName("neoForge"),
                neoforgeVersion = context.versionOrNull(catalog, "neoforge"),
                modId = modId,
                accessTransformerFile = accessTransformerFile,
                parchmentVersion = context.versionOrNull(catalog, "parchment"),
                parchmentMinecraftVersion = context.versionOrNull(catalog, "parchment-minecraft") ?: minecraftVersion,
            )
        }

        val expandProps = context.expandProperties(minecraftVersion, loader, catalog)
        val jsonExpandProps = expandProps.mapValues { (_, value) ->
            if (value is String) value.replace("\n", "\\n") else value
        }

        project.tasks.named("processResources", ProcessResources::class.java) {
            inputs.properties(expandProps.filterValues { it != null })
            filesMatching(listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                expand(expandProps)
            }
            filesMatching(listOf("pack.mcmeta", "*.mixins.json")) {
                expand(jsonExpandProps)
            }
        }

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
        }

        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                from(project.components.getByName("java"))
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
            }
            context.publishingRepositories(this, project.version.toString())
        }
    }

    private fun configureNeoGradleUserdev(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        modId: String,
    ) {
        project.configurations.getByName("runtimeClasspath")
            .extendsFrom(project.configurations.getByName("localRuntime"))

        project.dependencies.add("implementation", "net.neoforged:neoforge:${context.versionOrNull(catalog, "neoforge")}")

        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val runs = project.extensions.getByName("runs")
        GroovyGradleDsl.invoke(
            runs,
            "configureEach",
            GroovyGradleDsl.closure { run ->
                GroovyGradleDsl.invoke(run, "systemProperty", "forge.logging.console.level", "debug")
                GroovyGradleDsl.invoke(run, "systemProperty", "neoforge.enabledGameTestNamespaces", modId)
                GroovyGradleDsl.invoke(GroovyGradleDsl.get(run, "environmentVariables")!!, "put", "MOD_CLASSES", "{source_roots}")
                GroovyGradleDsl.invoke(GroovyGradleDsl.get(run, "environmentVariables")!!, "put", "MCP_MAPPINGS", "{mcp_mappings}")
                GroovyGradleDsl.invoke(run, "modSource", mainSourceSet)
            },
        )
        configureNamedRun(runs, "client")
        configureNamedRun(runs, "data") { run ->
                val arguments = GroovyGradleDsl.get(run, "arguments")
                if (arguments != null) {
                    GroovyGradleDsl.invoke(
                        arguments,
                        "addAll",
                        "--mod",
                        modId,
                        "--all",
                        "--output",
                        project.file("src/generated/resources").absolutePath,
                        "--existing",
                        project.file("src/main/resources").absolutePath,
                    )
                }
        }
        configureNamedRun(runs, "server") { run ->
            GroovyGradleDsl.invoke(run, "argument", "--nogui")
        }
    }

    private fun configureNeoForgeModDev(
        project: Project,
        neoForge: Any,
        neoforgeVersion: String?,
        modId: String,
        accessTransformerFile: File,
        parchmentVersion: String?,
        parchmentMinecraftVersion: String,
    ) {
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
                configureNamedRun(runs, "client") { run -> GroovyGradleDsl.invoke(run, "client") }
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

    private fun configureNamedRun(runs: Any, name: String, action: (Any) -> Unit = {}) {
        val run = runCatching { GroovyGradleDsl.invoke(runs, "maybeCreate", name) }.getOrNull()
            ?: runCatching { GroovyGradleDsl.invoke(runs, "create", name) }.getOrNull()
            ?: throw IllegalStateException("[NeoForge] Could not create run '$name'")
        action(run)
    }

    private fun isStonecutterNeoForgeProject(project: Project): Boolean =
        project.parent?.name == "neoforge"
}
