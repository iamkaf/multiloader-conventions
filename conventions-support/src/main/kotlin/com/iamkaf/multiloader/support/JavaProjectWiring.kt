package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

object JavaProjectWiring {
    fun configureArchiveName(project: Project, archiveName: String) {
        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set(archiveName)
        }
    }

    fun configureJavaBuild(project: Project, javaVersion: Int, includeGeneratedResources: Boolean = false) {
        project.extensions.configure(JavaPluginExtension::class.java) {
            withSourcesJar()
            withJavadocJar()
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }

        if (includeGeneratedResources) {
            project.extensions.findByType(SourceSetContainer::class.java)
                ?.named("main") {
                    resources.srcDir(project.file("src/main/generated"))
                }
        }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
        }
        configureJavadoc(project)
    }

    fun configureJavadoc(project: Project) {
        project.tasks.withType(Javadoc::class.java).configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        }
    }

    fun configureArchiveAndResourceDefaults(project: Project) {
        project.tasks.withType(ProcessResources::class.java).configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude(".cache/**")
        }
        project.tasks.withType(Jar::class.java).configureEach {
            exclude(".cache/**")
        }
        project.tasks.named("sourcesJar", Jar::class.java) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude(".cache/**")
        }
    }

    fun addBaseDependencies(project: Project, context: MultiloaderProjectContext, catalog: VersionCatalog) {
        project.dependencies.add("compileOnly", context.library(catalog, "mixin"))
        project.dependencies.add("compileOnly", context.library(catalog, "mixin-extras"))
        project.dependencies.add("compileOnly", "org.jetbrains:annotations:24.1.0")
        project.dependencies.add("annotationProcessor", context.library(catalog, "mixin-extras"))
        project.dependencies.add("implementation", context.library(catalog, "gson"))
    }

    fun configureResourceExpansion(
        project: Project,
        expandProperties: Map<String, Any?>,
        tomlPatterns: List<String>,
        jsonPatterns: List<String>,
    ) {
        val inputProps = expandProperties.filterValues { it != null }
        val jsonExpandProps = MetadataExpansion.jsonSafe(expandProperties)

        project.tasks.named("processResources", ProcessResources::class.java) {
            inputs.properties(inputProps)
            if (tomlPatterns.isNotEmpty()) {
                filesMatching(tomlPatterns) {
                    expand(expandProperties)
                }
            }
            if (jsonPatterns.isNotEmpty()) {
                filesMatching(jsonPatterns) {
                    expand(jsonExpandProps)
                }
            }
        }
    }

    fun configureJarManifest(
        project: Project,
        identity: ProjectIdentity,
        implementationTitle: String = identity.implementationTitle,
        mixinConfigs: List<String> = emptyList(),
    ) {
        project.tasks.named("jar", Jar::class.java) {
            manifest.attributes(
                mapOf(
                    "Specification-Title" to identity.modName,
                    "Specification-Vendor" to identity.authors,
                    "Specification-Version" to project.version.toString(),
                    "Implementation-Title" to implementationTitle,
                    "Implementation-Version" to project.version.toString(),
                    "Implementation-Vendor" to identity.authors,
                    "Built-On-Minecraft" to identity.minecraftVersion,
                    "Built-By" to "multiloader-conventions",
                ),
            )
            if (mixinConfigs.isNotEmpty()) {
                manifest.attributes(mapOf("MixinConfigs" to mixinConfigs.joinToString(",")))
            }
        }
    }

    fun configureLicensePackaging(project: Project, licenseFile: File, modName: String) {
        if (!licenseFile.exists()) return

        listOf("jar", "sourcesJar").forEach { taskName ->
            project.tasks.named(taskName, Jar::class.java) {
                from(licenseFile) {
                    rename { "${it}_$modName" }
                }
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                exclude(".cache/**")
            }
        }
    }

    fun configureCapabilities(project: Project, identity: ProjectIdentity, extraCapabilities: List<String>) {
        listOf("apiElements", "runtimeElements", "sourcesElements", "javadocElements").forEach { variant ->
            project.configurations.named(variant) {
                outgoing.capability("${identity.group}:${identity.archiveName}:${identity.version}")
                extraCapabilities.forEach { capability ->
                    outgoing.capability("${identity.group}:$capability:${identity.version}")
                }
            }
        }
    }
}
