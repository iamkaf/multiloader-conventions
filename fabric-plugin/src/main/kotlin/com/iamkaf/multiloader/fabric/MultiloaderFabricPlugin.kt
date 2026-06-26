package com.iamkaf.multiloader.fabric

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

class MultiloaderFabricPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "multiloaderFabric",
            MultiloaderFabricExtension::class.java,
        )

        if (!isStonecutterFabricProject(project)) {
            applyFlatFabricPlugin(project, extension)
            return
        }

        applyStonecutterFabricPlugin(project, extension)
    }

    private fun applyFlatFabricPlugin(project: Project, extension: MultiloaderFabricExtension) {
        project.pluginManager.apply(MultiloaderPlatformPlugin::class.java)
        val loomPluginId = if (ConventionSupport.isUnobfuscatedMinecraft(project)) {
            "net.fabricmc.fabric-loom"
        } else {
            "fabric-loom"
        }
        project.pluginManager.apply(loomPluginId)

        project.pluginManager.withPlugin(loomPluginId) {
            ConventionSupport.configureFabricBaseDependencies(project)
            configureFabricDatagen(project, extension)

            val loom = project.extensions.getByName("loom")
            val accessWidener = ConventionSupport.commonFile(
                project,
                "src/main/resources/${ConventionSupport.requiredProperty(project, "mod.id")}.accesswidener",
            )
            configureLoom(project, loom, ConventionSupport.requiredProperty(project, "mod.id"), accessWidener)
        }
    }

    private fun applyStonecutterFabricPlugin(project: Project, extension: MultiloaderFabricExtension) {
        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val context = MultiloaderProjectContext.of(project)
        val minecraftVersion = context.requiredProperty("project.minecraft")
        val catalog = context.catalogFor(minecraftVersion)
        val useUnobfuscatedMinecraft = context.useUnobfuscatedMinecraft(minecraftVersion)
        val useLegacyFabricApiModules = VersionPolicy.usesLegacyFabricApiModules(minecraftVersion)
        val hasParchment = context.versionOrNull(catalog, "parchment") != null
        val modMenuVersion = context.versionOrNull(catalog, "modmenu")
        val hasModMenu = modMenuVersion != null && modMenuVersion != "null"
        val modId = context.requiredProperty("mod.id")
        val modName = context.requiredProperty("mod.name")
        val loader = context.requiredProperty("loader")
        val teaKitVersion = context.versionOrNull(catalog, "teakit")
        val teaKitLibrary = catalog.findLibrary("teakit-fabric")
        val hasTeaKit = teaKitLibrary.isPresent && teaKitVersion != null && teaKitVersion != "null"
        val useTeaKit = project.providers.systemProperty("$modId.withTeaKit")
            .orElse(project.providers.gradleProperty("$modId.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get() && modId != "teakit"
        val useModernFabricRuntime = !minecraftVersion.startsWith("1.")
        val commonProject = project.project(":common:$minecraftVersion")
        val versionDir = project.rootProject.file("versions/$minecraftVersion")
        val versionAccessWidener = versionDir.toPath()
            .resolve("common/src/main/resources/$modId.accesswidener")
            .toFile()
        val accessWidener = if (versionAccessWidener.exists()) {
            versionAccessWidener
        } else {
            project.rootProject.file("common/src/main/resources/$modId.accesswidener")
        }
        val loomPluginId = if (useUnobfuscatedMinecraft) "net.fabricmc.fabric-loom" else "fabric-loom"

        project.pluginManager.apply(loomPluginId)

        project.group = context.requiredProperty("project.group")
        project.version = context.requiredProperty("project.version")

        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("$modId-$loader")
        }

        context.sharedRepositories()
        StonecutterSourceLayout.configureLoader(project, "fabric", minecraftVersion, commonProject)

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
        project.dependencies.add("minecraft", context.library(catalog, "minecraft"))

        if (!useUnobfuscatedMinecraft) {
            configureFabricMappings(project, context, catalog, hasParchment)
            addFabricLoader(project, "modImplementation", context, catalog, minecraftVersion)
        } else {
            addFabricLoader(project, "implementation", context, catalog, minecraftVersion)
        }

        val fabricApiConfiguration = if (useUnobfuscatedMinecraft) "implementation" else "modImplementation"
        if (useLegacyFabricApiModules) {
            addLegacyFabricApiModules(project, fabricApiConfiguration)
        } else {
            project.dependencies.add(fabricApiConfiguration, context.library(catalog, "fabric-api"))
        }
        if (hasModMenu && !useUnobfuscatedMinecraft) {
            project.dependencies.add("modImplementation", context.library(catalog, "modmenu"))
        }

        if (useTeaKit && hasTeaKit) {
            project.dependencies.add(
                if (useModernFabricRuntime) "runtimeOnly" else "modLocalRuntime",
                teaKitLibrary.get(),
            )
        }

        configureFabricDatagen(project, extension)
        configureLoom(project, project.extensions.getByName("loom"), modId, accessWidener)

        val expandProps = context.expandProperties(minecraftVersion, loader, catalog)
        val jsonExpandProps = expandProps.mapValues { (_, value) ->
            if (value is String) value.replace("\n", "\\n") else value
        }

        project.tasks.named("processResources", ProcessResources::class.java) {
            inputs.properties(expandProps.filterValues { it != null })
            filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json")) {
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

    private fun configureFabricDatagen(project: Project, extension: MultiloaderFabricExtension) {
        extension.commonDatagen.finalizeValueOnRead()
        project.afterEvaluate {
            if (!extension.commonDatagen.get()) return@afterEvaluate
            val fabricApi = project.extensions.findByName("fabricApi")
                ?: throw IllegalStateException("Cannot enable common Fabric datagen because the fabricApi extension is not available.")
            GroovyGradleDsl.invoke(
                fabricApi,
                "configureDataGeneration",
                GroovyGradleDsl.closure { datagen ->
                    GroovyGradleDsl.set(datagen, "client", true)
                    GroovyGradleDsl.set(datagen, "outputDirectory", project.rootProject.file("common/src/main/generated"))
                },
            )
        }
    }

    private fun configureFabricMappings(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        hasParchment: Boolean,
    ) {
        val loom = project.extensions.getByName("loom")
        val mappings = if (hasParchment) {
            GroovyGradleDsl.invoke(
                loom,
                "layered",
                GroovyGradleDsl.closure { layered ->
                    GroovyGradleDsl.invoke(layered, "officialMojangMappings")
                    GroovyGradleDsl.invoke(layered, "parchment", context.library(catalog, "parchment"))
                },
            )
        } else {
            GroovyGradleDsl.invoke(loom, "officialMojangMappings")
        }
        project.dependencies.add("mappings", requireNotNull(mappings) { "Fabric Loom did not provide mappings for ${project.path}" })
    }

    private fun addFabricLoader(
        project: Project,
        configuration: String,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
    ) {
        if (minecraftVersion == "1.18.2") {
            val dependency = project.dependencies.create("net.fabricmc:fabric-loader:0.14.9") as ExternalModuleDependency
            dependency.version {
                strictly("0.14.9")
            }
            project.dependencies.add(configuration, dependency)
        } else {
            project.dependencies.add(configuration, context.library(catalog, "fabric-loader"))
        }
    }

    private fun addLegacyFabricApiModules(project: Project, configuration: String) {
        listOf(
            "net.fabricmc.fabric-api:fabric-api-base:0.4.0+3cc0f0907d",
            "net.fabricmc.fabric-api:fabric-command-api-v1:1.0.9+6a2618f53a",
            "net.fabricmc.fabric-api:fabric-networking-api-v1:1.0.5+3cc0f0907d",
            "net.fabricmc.fabric-api:fabric-lifecycle-events-v1:1.2.2+3cc0f0907d",
            "net.fabricmc.fabric-api:fabric-resource-loader-v0:0.4.2+ca58154a7d",
        ).forEach { dependency ->
            project.dependencies.add(configuration, dependency)
        }
    }

    private fun configureLoom(project: Project, loom: Any, modId: String, accessWidener: File) {
        if (accessWidener.exists()) {
            val accessWidenerPath = GroovyGradleDsl.get(loom, "accessWidenerPath")
            if (accessWidenerPath != null) {
                GroovyGradleDsl.invoke(accessWidenerPath, "set", accessWidener)
            }
        }

        GroovyGradleDsl.invoke(
            loom,
            "mixin",
            GroovyGradleDsl.closure { mixin ->
                val defaultRefmapName = GroovyGradleDsl.get(mixin, "defaultRefmapName")
                if (defaultRefmapName != null) {
                    GroovyGradleDsl.invoke(defaultRefmapName, "set", "$modId.refmap.json")
                }
            },
        )

        GroovyGradleDsl.invoke(
            loom,
            "runs",
            GroovyGradleDsl.closure { runs ->
                GroovyGradleDsl.invoke(
                    runs,
                    "client",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.invoke(run, "client")
                        GroovyGradleDsl.invoke(run, "setConfigName", "Fabric Client")
                        GroovyGradleDsl.invoke(run, "ideConfigGenerated", true)
                        GroovyGradleDsl.invoke(run, "runDir", "runs/client")
                    },
                )
                GroovyGradleDsl.invoke(
                    runs,
                    "server",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.invoke(run, "server")
                        GroovyGradleDsl.invoke(run, "setConfigName", "Fabric Server")
                        GroovyGradleDsl.invoke(run, "ideConfigGenerated", true)
                        GroovyGradleDsl.invoke(run, "runDir", "runs/server")
                    },
                )
            },
        )
    }

    private fun isStonecutterFabricProject(project: Project): Boolean =
        project.parent?.name == "fabric"
}
