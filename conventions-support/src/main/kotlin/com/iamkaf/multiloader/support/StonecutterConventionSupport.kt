package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import java.net.URI

object StonecutterConventionSupport {
    @JvmStatic
    fun requiredProp(project: Project, name: String): String =
        MultiloaderProjectContext.of(project).requiredProperty(name)

    @JvmStatic
    fun optionalProp(project: Project, name: String): String? =
        MultiloaderProjectContext.of(project).optionalProperty(name)

    @JvmStatic
    fun catalogName(minecraftVersion: String): String =
        VersionPolicy.catalogName(minecraftVersion)

    @JvmStatic
    fun catalogFor(project: Project, minecraftVersion: String): VersionCatalog =
        MultiloaderProjectContext.of(project).catalogFor(minecraftVersion)

    @JvmStatic
    fun versionOrNull(catalog: VersionCatalog, alias: String): String? {
        val version = catalog.findVersion(alias)
        if (!version.isPresent) return null

        val resolved = version.get().requiredVersion
        return resolved?.takeUnless { it.isBlank() || it == "null" }
    }

    @JvmStatic
    fun library(catalog: VersionCatalog, alias: String): Any {
        val dependency = catalog.findLibrary(alias)
        if (!dependency.isPresent) {
            throw org.gradle.api.GradleException("Missing library alias '$alias'")
        }
        return dependency.get()
    }

    @JvmStatic
    fun useUnobfuscatedMinecraft(minecraftVersion: String): Boolean =
        VersionPolicy.useUnobfuscatedMinecraft(minecraftVersion)

    @JvmStatic
    fun sharedRepositories(project: Project) {
        MultiloaderProjectContext.of(project).sharedRepositories()
    }

    @JvmStatic
    fun publishingRepositories(publishing: PublishingExtension, version: String) {
        publishing.repositories.maven { repo ->
            repo.name = "KafMaven"
            repo.url = URI.create(
                if (version.endsWith("-SNAPSHOT")) "https://z.kaf.sh/snapshots"
                else "https://z.kaf.sh/releases",
            )
            repo.credentials { credentials ->
                credentials.username = System.getenv("MAVEN_PUBLISH_USERNAME")
                credentials.password = System.getenv("MAVEN_PUBLISH_PASSWORD")
            }
        }
    }

    @JvmStatic
    fun mixinConfigs(project: Project, loader: String): List<String> =
        MultiloaderProjectContext.of(project).mixinConfigs(loader)

    @JvmStatic
    fun expandProps(
        project: Project,
        minecraftVersion: String,
        loader: String,
        catalog: VersionCatalog,
    ): Map<String, Any?> =
        MultiloaderProjectContext.of(project).expandProperties(minecraftVersion, loader, catalog)

    @JvmStatic
    fun versionCatalog(project: Project): VersionCatalog {
        val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java)
            ?: throw IllegalStateException("Missing version catalogs for ${project.path}")
        return catalogs.named("libs")
    }
}
