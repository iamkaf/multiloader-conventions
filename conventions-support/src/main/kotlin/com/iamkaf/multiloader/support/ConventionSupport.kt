package com.iamkaf.multiloader.support

import com.iamkaf.multiloader.support.flat.FlatCommonSourceBridge
import com.iamkaf.multiloader.support.flat.FlatFabricDependencies
import com.iamkaf.multiloader.support.flat.FlatProjectAccess
import com.iamkaf.multiloader.support.flat.FlatProjectConventions
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources

object ConventionSupport {
    @JvmField
    val LOADER_PROJECTS: List<String> = FlatProjectConventions.loaderProjects

    @JvmStatic
    fun configureCommonProject(project: Project) = FlatProjectConventions.configureCommonProject(project)

    @JvmStatic
    fun configureLoaderBridge(project: Project) = FlatCommonSourceBridge.configureLoaderBridge(project)

    @JvmStatic
    fun configureFabricBaseDependencies(project: Project) = FlatFabricDependencies.configureBaseDependencies(project)

    @JvmStatic
    fun configureRepositories(project: Project) = RepositoryPolicy.configureProjectRepositories(project)

    @JvmStatic
    fun configureCoordinates(project: Project) = FlatProjectConventions.configureCoordinates(project)

    @JvmStatic
    fun configureArchiveNaming(project: Project) {
        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("${requiredProperty(project, "mod.id")}-${project.name}")
        }
    }

    @JvmStatic
    fun configureJava(project: Project) {
        val javaVersion = project.findProperty("project.java")?.toString()?.toIntOrNull()
        project.extensions.configure(JavaPluginExtension::class.java) {
            withSourcesJar()
            withJavadocJar()
            if (javaVersion != null) {
                toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
            }
        }

        project.extensions.findByType(SourceSetContainer::class.java)
            ?.named("main") {
                resources.srcDir(project.file("src/main/generated"))
            }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
        }

        configureJavadoc(project)
    }

    @JvmStatic
    fun configureJavadoc(project: Project) = JavaProjectWiring.configureJavadoc(project)

    @JvmStatic
    fun configureResourceMetadata(project: Project) {
        project.tasks.withType(ProcessResources::class.java).configureEach {
            val expandProps = buildExpandProperties(project)
            val inputProps = expandProps.filterValues { it != null }
            val jsonExpandProps = expandProps.mapValues { (_, value) ->
                if (value is String) value.replace("\n", "\\n") else value
            }

            exclude(".cache/**")
            filesMatching(listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
                expand(expandProps)
            }
            filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json")) {
                expand(jsonExpandProps)
            }
            inputs.properties(inputProps)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    @JvmStatic
    fun configureManifests(project: Project) {
        project.tasks.named("jar", Jar::class.java) {
            manifest.attributes(
                mapOf(
                    "Specification-Title" to requiredProperty(project, "mod.name"),
                    "Specification-Vendor" to optionalProperty(project, "mod.authors"),
                    "Specification-Version" to project.version.toString(),
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version.toString(),
                    "Implementation-Vendor" to optionalProperty(project, "mod.authors"),
                    "Built-On-Minecraft" to versionAlias(project, "minecraft"),
                    "Built-By" to "multiloader-conventions",
                ),
            )
        }
    }

    @JvmStatic
    fun configureLicensePackaging(project: Project) {
        val licenseFile = resolveLicenseFile(project)
        if (!licenseFile.exists()) return

        listOf("jar", "sourcesJar").forEach { taskName ->
            project.tasks.named(taskName, Jar::class.java) {
                from(licenseFile) {
                    rename { "${it}_${requiredProperty(project, "mod.name")}" }
                }
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                exclude(".cache/**")
            }
        }
    }

    @JvmStatic
    fun configureCapabilities(project: Project) = FlatProjectConventions.configureCapabilities(project)

    @JvmStatic
    fun configurePublishing(project: Project) = MavenPublicationWiring.configureFlatJavaPublication(project)

    @JvmStatic
    fun configureCommonArtifacts(project: Project) = FlatProjectConventions.configureCommonArtifacts(project)

    @JvmStatic
    fun commonFile(project: Project, relativePath: String): java.io.File =
        FlatProjectAccess.commonFile(project, relativePath)

    @JvmStatic
    fun collectMixinConfigs(project: Project, loaderName: String): List<String> =
        FlatProjectAccess.collectMixinConfigs(project, loaderName)

    @JvmStatic
    fun resolveLicenseFile(project: Project): java.io.File =
        FlatProjectAccess.resolveLicenseFile(project)

    @JvmStatic
    fun buildExpandProperties(project: Project): Map<String, Any?> = MetadataExpansion.flat(project)

    @JvmStatic
    fun versionCatalog(project: Project): VersionCatalog = FlatProjectAccess.versionCatalog(project)

    @JvmStatic
    fun requiredLibrary(project: Project, alias: String): Any = FlatProjectAccess.requiredLibrary(project, alias)

    @JvmStatic
    fun versionAlias(project: Project, alias: String): String = FlatProjectAccess.versionAlias(project, alias)

    @JvmStatic
    fun optionalVersionAlias(project: Project, alias: String): String? = FlatProjectAccess.optionalVersionAlias(project, alias)

    @JvmStatic
    fun requiredProperty(project: Project, propertyName: String): String =
        FlatProjectAccess.requiredProperty(project, propertyName)

    @JvmStatic
    fun optionalProperty(project: Project, propertyName: String): String? =
        FlatProjectAccess.optionalProperty(project, propertyName)

    @JvmStatic
    fun isUnobfuscatedMinecraft(project: Project): Boolean =
        FlatProjectAccess.isUnobfuscatedMinecraft(project)
}
