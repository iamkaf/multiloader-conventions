package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import java.net.URI
import java.util.Properties

class MultiloaderProjectContext private constructor(private val project: Project) {
    companion object {
        @JvmStatic
        fun of(project: Project): MultiloaderProjectContext = MultiloaderProjectContext(project)
    }

    fun requiredProperty(name: String): String =
        optionalProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Missing required property '$name' for ${project.path}")

    fun optionalProperty(name: String): String? {
        if (name == "loader") {
            return project.parent?.name
        }

        val versionKey = resolveVersionKey()
        if (!versionKey.isNullOrBlank()) {
            val versionProps = versionProperties(versionKey)
            val versionValue = versionProps.getProperty(name)
            if (!versionValue.isNullOrBlank()) {
                return versionValue
            }
        }

        val directValue = scopedProperty(name)
        if (!directValue.isNullOrBlank()) {
            if (name == "publish.game-versions" && directValue == "auto") {
                return optionalProperty("project.minecraft")
            }
            return directValue
        }

        return null
    }

    fun minecraftVersion(): String = requiredProperty("project.minecraft")

    fun loader(): String = requiredProperty("loader")

    fun catalogFor(minecraftVersion: String = minecraftVersion()): VersionCatalog {
        val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
        val versionCatalog = catalogs.find(VersionPolicy.catalogName(minecraftVersion))
        return if (versionCatalog.isPresent) versionCatalog.get() else catalogs.named("libs")
    }

    fun versionOrNull(catalog: VersionCatalog, alias: String): String? {
        val version = catalog.findVersion(alias)
        if (!version.isPresent) return null
        val resolved = version.get().requiredVersion
        return resolved?.takeUnless { it.isBlank() || it == "null" }
    }

    fun library(catalog: VersionCatalog, alias: String): Any {
        val dependency = catalog.findLibrary(alias)
        if (!dependency.isPresent) {
            throw GradleException("Missing library alias '$alias'")
        }
        return dependency.get()
    }

    fun useUnobfuscatedMinecraft(minecraftVersion: String = minecraftVersion()): Boolean =
        VersionPolicy.useUnobfuscatedMinecraft(minecraftVersion)

    fun sharedRepositories() {
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        project.repositories.maven { repo ->
            repo.name = "TerraformersMC"
            repo.url = project.uri("https://maven.terraformersmc.com/")
            repo.metadataSources { sources ->
                sources.mavenPom()
                sources.artifact()
            }
        }
        project.repositories.maven { repo ->
            repo.name = "Nucleoid"
            repo.url = project.uri("https://maven.nucleoid.xyz/")
        }
        project.repositories.maven { repo ->
            repo.name = "Sponge"
            repo.url = project.uri("https://repo.spongepowered.org/repository/maven-public")
        }
        project.repositories.maven { repo ->
            repo.name = "ParchmentMC"
            repo.url = project.uri("https://maven.parchmentmc.org/")
        }
        project.repositories.maven { repo ->
            repo.name = "NeoForge"
            repo.url = project.uri("https://maven.neoforged.net/releases")
        }
        project.repositories.maven { repo ->
            repo.name = "BlameJared"
            repo.url = project.uri("https://maven.blamejared.com")
        }
        project.repositories.maven { repo ->
            repo.name = "Modrinth"
            repo.url = project.uri("https://api.modrinth.com/maven")
        }
        project.repositories.maven { repo ->
            repo.name = "Kaf Maven"
            repo.url = project.uri("https://maven.kaf.sh")
        }
        project.repositories.maven { repo ->
            repo.name = "Fuzs Mod Resources"
            repo.url = project.uri("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/")
        }
    }

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

    fun mixinConfigs(loader: String): List<String> {
        val modId = requiredProperty("mod.id")
        return listOfNotNull(
            if (project.rootProject.file("common/src/main/resources/$modId.mixins.json").exists()) {
                "$modId.mixins.json"
            } else {
                null
            },
            if (project.rootProject.file("$loader/src/main/resources/$modId.$loader.mixins.json").exists()) {
                "$modId.$loader.mixins.json"
            } else {
                null
            },
        )
    }

    fun expandProperties(minecraftVersion: String, loader: String, catalog: VersionCatalog): Map<String, Any?> {
        val minecraftVersionRange = if (loader == "fabric") {
            fabricMinecraftDependency(minecraftVersion, optionalProperty("mod.minecraft-range"))
        } else {
            optionalProperty("mod.minecraft-range")
        }

        return linkedMapOf(
            "version" to requiredProperty("project.version"),
            "group" to requiredProperty("project.group"),
            "minecraft_version" to minecraftVersion,
            "minecraft_version_range" to minecraftVersionRange,
            "fabric_version_range" to optionalProperty("mod.fabric-range"),
            "fabric_version" to versionOrNull(catalog, "fabric-api"),
            "fabric_loader_version" to versionOrNull(catalog, "fabric-loader"),
            "mod_menu_version" to versionOrNull(catalog, "modmenu"),
            "mod_name" to requiredProperty("mod.name"),
            "mod_author" to optionalProperty("mod.authors"),
            "mod_id" to requiredProperty("mod.id"),
            "license" to optionalProperty("mod.license"),
            "description" to optionalProperty("mod.description"),
            "neoforge_version" to versionOrNull(catalog, "neoforge"),
            "neoforge_loader_version_range" to optionalProperty("mod.neoforge-loader-range"),
            "forge_version" to versionOrNull(catalog, "forge"),
            "forge_loader_version_range" to optionalProperty("mod.forge-loader-range"),
            "amber_version" to versionOrNull(catalog, "amber"),
            "konfig_version" to versionOrNull(catalog, "konfig"),
            "parchment_minecraft" to versionOrNull(catalog, "parchment-minecraft"),
            "parchment_version" to versionOrNull(catalog, "parchment"),
            "credits" to optionalProperty("mod.credits"),
            "java_version" to requiredProperty("project.java"),
            "mixin_compat_common" to requiredProperty("mixin.compat.common"),
            "mixin_compat_fabric" to requiredProperty("mixin.compat.fabric"),
            "mixin_compat_forge" to requiredProperty("mixin.compat.forge"),
            "mixin_compat_neoforge" to requiredProperty("mixin.compat.neoforge"),
        )
    }

    private fun resolveVersionKey(): String? {
        val directVersionDir = project.rootProject.file("versions/${project.name}")
        if (directVersionDir.isDirectory) {
            return project.name
        }

        scopedProperty("multiloader.stonecutter.active")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }

        val targetVersions = scopedProperty(MultiloaderTargetScope.VERSIONS_PROPERTY)?.trim()
        if (!targetVersions.isNullOrBlank() && !targetVersions.equals("all", ignoreCase = true)) {
            val targets = targetVersions.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
                .distinct()
            if (targets.size == 1) {
                return targets.first()
            }
        }

        val stonecutterBuild = project.extensions.findByName("stonecutterBuild")
            ?: project.extensions.findByName("stonecutter")
        val currentVersion = stonecutterBuild?.let { extension ->
            runCatching {
                val current = extension.javaClass.methods.firstOrNull { it.name == "getCurrent" && it.parameterCount == 0 }
                    ?.invoke(extension)
                    ?: extension.javaClass.fields.firstOrNull { it.name == "current" }?.get(extension)
                val version = current?.javaClass?.methods?.firstOrNull { it.name == "getVersion" && it.parameterCount == 0 }
                    ?.invoke(current)
                    ?: current?.javaClass?.fields?.firstOrNull { it.name == "version" }?.get(current)
                version?.toString()
            }.getOrNull()
        }

        return currentVersion?.takeIf { it.isNotBlank() }
    }

    private fun versionProperties(versionKey: String): Properties {
        val cacheKey = "versionProps:$versionKey"
        val extras = project.rootProject.extensions.extraProperties
        if (extras.has(cacheKey)) {
            return extras.get(cacheKey) as Properties
        }

        val properties = Properties()
        val versionFile = project.rootProject.file("versions/$versionKey/gradle.properties")
        if (versionFile.isFile) {
            versionFile.inputStream().use(properties::load)
        } else {
            properties.putAll(VersionPolicy.metadataProperties(versionKey))
        }
        extras.set(cacheKey, properties)
        return properties
    }

    private fun scopedProperty(name: String): String? =
        (project.findProperty(name) ?: project.rootProject.findProperty(name))?.toString()

    private fun fabricMinecraftDependency(minecraftVersion: String?, configuredRange: String?): String? =
        if (minecraftVersion == null || minecraftVersion.contains("-rc-")) configuredRange else minecraftVersion
}
