package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.StonecutterSourceLayout
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
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

class MultiloaderCommonPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!isCommonProject(project)) {
            ConventionSupport.configureCommonProject(project)
            return
        }

        val context = MultiloaderProjectContext.of(project)

        project.pluginManager.apply("com.iamkaf.multiloader.core")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("maven-publish")

        val minecraftVersion = context.requiredProperty("project.minecraft")
        val catalog = context.catalogFor(minecraftVersion)
        val modId = context.requiredProperty("mod.id")
        val useFabricLoomCommon = usesFabricLoomCommon(minecraftVersion)
        val useLegacyForgeCommon = !useFabricLoomCommon && context.versionOrNull(catalog, "neoform") == null
        val hasParchment = context.versionOrNull(catalog, "parchment") != null
        val accessTransformerFile = resolveAccessTransformerFile(project)
        val usesStonecutter = project.tasks.findByName("stonecutterGenerate") != null

        if (useFabricLoomCommon) {
            project.pluginManager.apply("fabric-loom")
        } else {
            project.pluginManager.apply(
                if (useLegacyForgeCommon) "net.neoforged.moddev.legacyforge" else "net.neoforged.moddev",
            )
        }

        project.group = context.requiredProperty("project.group")
        project.version = context.requiredProperty("project.version")

        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("$modId-common")
        }

        context.sharedRepositories()
        StonecutterSourceLayout.configureCommon(project, minecraftVersion, usesStonecutter)

        project.extensions.configure(JavaPluginExtension::class.java) {
            withSourcesJar()
            withJavadocJar()
            toolchain.languageVersion.set(
                JavaLanguageVersion.of(context.requiredProperty("project.java").toInt()),
            )
        }

        when {
            useFabricLoomCommon -> configureFabricLoomCommon(project, context, catalog, hasParchment)
            useLegacyForgeCommon -> configureLegacyForgeCommon(project, context, catalog, hasParchment, accessTransformerFile)
            else -> configureNeoFormCommon(project, context, catalog, hasParchment, accessTransformerFile)
        }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
        }
        ConventionSupport.configureJavadoc(project)

        project.tasks.withType(ProcessResources::class.java).configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude(".cache/**")
        }

        project.tasks.withType(Jar::class.java).configureEach {
            exclude(".cache/**")
        }

        project.tasks.named("sourcesJar", Jar::class.java) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        if (usesStonecutter) {
            StonecutterSourceLayout.attachStagingDependencies(project)
        }

        val commonJava = project.configurations.create("commonJava") {
            isCanBeResolved = false
            isCanBeConsumed = true
        }
        val commonResources = project.configurations.create("commonResources") {
            isCanBeResolved = false
            isCanBeConsumed = true
        }

        project.afterEvaluate {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            val main = sourceSets.getByName("main")
            main.java.sourceDirectories.files.forEach { directory ->
                project.artifacts.add(commonJava.name, directory)
            }
            main.resources.sourceDirectories.files.forEach { directory ->
                project.artifacts.add(commonResources.name, directory)
            }
        }

        project.dependencies.add("compileOnly", "org.jetbrains:annotations:24.1.0")
        project.dependencies.add("compileOnly", context.library(catalog, "mixin"))
        project.dependencies.add("compileOnly", context.library(catalog, "mixin-extras"))
        project.dependencies.add("annotationProcessor", context.library(catalog, "mixin-extras"))
        project.dependencies.add("implementation", context.library(catalog, "gson"))

        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
                if (useFabricLoomCommon) {
                    val remapJar = project.tasks.named("remapJar")
                    artifact(remapJar) {
                        builtBy(remapJar)
                    }
                    val remapSourcesJar = project.tasks.findByName("remapSourcesJar")
                    if (remapSourcesJar != null) {
                        val remapSources = project.tasks.named("remapSourcesJar")
                        artifact(remapSources) {
                            builtBy(remapSources)
                        }
                    } else {
                        val sourcesJar = project.tasks.named("sourcesJar")
                        artifact(sourcesJar) {
                            builtBy(sourcesJar)
                        }
                    }
                    val javadocJar = project.tasks.named("javadocJar")
                    artifact(javadocJar) {
                        builtBy(javadocJar)
                    }
                } else {
                    from(project.components.getByName("java"))
                }
            }

            context.publishingRepositories(this, project.version.toString())
        }

        listOf("apiElements", "runtimeElements", "sourcesElements", "javadocElements").forEach { variant ->
            project.configurations.named(variant) {
                outgoing.capability(
                    "${project.group}:${project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()}:${project.version}",
                )
                outgoing.capability("${project.group}:${context.requiredProperty("mod.id")}:${project.version}")
            }
        }
    }

    private fun configureFabricLoomCommon(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        hasParchment: Boolean,
    ) {
        project.dependencies.add("minecraft", context.library(catalog, "minecraft"))
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
        project.dependencies.add("modImplementation", context.library(catalog, "fabric-loader"))
    }

    private fun configureLegacyForgeCommon(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        hasParchment: Boolean,
        accessTransformerFile: File,
    ) {
        val legacyForge = project.extensions.getByName("legacyForge")
        GroovyGradleDsl.set(legacyForge, "mcpVersion", context.versionOrNull(catalog, "minecraft"))

        if (accessTransformerFile.exists()) {
            GroovyGradleDsl.set(legacyForge, "accessTransformers", listOf(accessTransformerFile.absolutePath))
        }

        if (hasParchment) {
            GroovyGradleDsl.invoke(
                legacyForge,
                "parchment",
                GroovyGradleDsl.closure { parchment ->
                    GroovyGradleDsl.invoke(
                        parchment,
                        "setMinecraftVersion",
                        context.versionOrNull(catalog, "parchment-minecraft") ?: context.versionOrNull(catalog, "minecraft"),
                    )
                    GroovyGradleDsl.invoke(parchment, "setMappingsVersion", context.versionOrNull(catalog, "parchment"))
                },
            )
        }
    }

    private fun configureNeoFormCommon(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        hasParchment: Boolean,
        accessTransformerFile: File,
    ) {
        val neoForge = project.extensions.getByName("neoForge")
        GroovyGradleDsl.set(neoForge, "neoFormVersion", context.versionOrNull(catalog, "neoform"))

        if (accessTransformerFile.exists()) {
            val accessTransformers = GroovyGradleDsl.get(neoForge, "accessTransformers")
            if (accessTransformers != null) {
                GroovyGradleDsl.invoke(accessTransformers, "from", accessTransformerFile.absolutePath)
            }
        }

        if (hasParchment) {
            GroovyGradleDsl.invoke(
                neoForge,
                "parchment",
                GroovyGradleDsl.closure { parchment ->
                    GroovyGradleDsl.invoke(
                        parchment,
                        "setMinecraftVersion",
                        context.versionOrNull(catalog, "parchment-minecraft") ?: context.versionOrNull(catalog, "minecraft"),
                    )
                    GroovyGradleDsl.invoke(parchment, "setMappingsVersion", context.versionOrNull(catalog, "parchment"))
                },
            )
        }
    }

    private fun isCommonProject(project: Project): Boolean =
        project.name == "common" || project.parent?.name == "common"

    private fun usesFabricLoomCommon(minecraftVersion: String): Boolean =
        minecraftVersion == "1.16.5" ||
            minecraftVersion.startsWith("1.16.") ||
            minecraftVersion == "1.16" ||
            minecraftVersion.startsWith("1.15.") ||
            minecraftVersion == "1.15" ||
            minecraftVersion.startsWith("1.14.")

    private fun resolveAccessTransformerFile(project: Project): File =
        if (project.parent?.name == "common") {
            project.rootProject.file("common/src/main/resources/META-INF/accesstransformer.cfg")
        } else {
            project.file("src/main/resources/META-INF/accesstransformer.cfg")
        }
}
