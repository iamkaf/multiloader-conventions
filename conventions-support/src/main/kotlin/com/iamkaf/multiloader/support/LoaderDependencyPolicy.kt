package com.iamkaf.multiloader.support

import com.iamkaf.multiloader.support.adapters.FabricLoomAdapter
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog

object LoaderDependencyPolicy {
    fun addCommonWorkspaceLibraries(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
        toolchainStrategy: CommonToolchainStrategy,
    ) {
        if (toolchainStrategy == CommonToolchainStrategy.FABRIC_LOOM) {
            addOptional(project, context, catalog, "modImplementation", "amber-fabric", identity)
            addOptional(project, context, catalog, "modCompileOnly", "konfig-fabric", identity)
        } else {
            addOptional(project, context, catalog, "implementation", "amber", identity)
            addOptional(project, context, catalog, "compileOnly", "konfig", identity)
        }
    }

    fun addFabricLoaderLibraries(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
        minecraftVersion: String,
    ) {
        if (VersionPolicy.useUnobfuscatedMinecraft(minecraftVersion)) {
            addOptional(project, context, catalog, "compileOnly", "amber", identity)
            addOptional(project, context, catalog, "runtimeOnly", "amber-fabric", identity)
            addOptional(project, context, catalog, "implementation", "konfig-fabric", identity)
        } else {
            addOptional(project, context, catalog, "modImplementation", "amber-fabric", identity)
            addOptional(project, context, catalog, "modImplementation", "konfig-fabric", identity)
        }
    }

    fun addFabricApi(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
        useUnobfuscatedMinecraft: Boolean,
    ) {
        val fabricApiConfiguration = if (useUnobfuscatedMinecraft) "implementation" else "modImplementation"
        if (VersionPolicy.usesLegacyFabricApiModules(minecraftVersion)) {
            FabricLoomAdapter.addLegacyFabricApiModules(project, fabricApiConfiguration)
            return
        }

        project.dependencies.add(fabricApiConfiguration, context.library(catalog, "fabric-api"))
        if (!useUnobfuscatedMinecraft) {
            project.dependencies.add("modLocalRuntime", context.library(catalog, "fabric-api"))
        }
    }

    fun addFabricDatagenApi(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
    ) {
        val dependency = context.libraryOrNull(catalog, "fabric-data-generation-api-v1") ?: return
        val needsRuntime = project.gradle.startParameter.taskNames.any { it.contains("datagen", ignoreCase = true) }

        when {
            minecraftVersion in fabric116DatagenVersions -> {
                project.dependencies.add("compileOnly", dependency)
                if (needsRuntime) {
                    project.dependencies.add("modImplementation", dependency)
                }
            }
            minecraftVersion == "1.17" -> project.dependencies.add("modImplementation", dependency)
        }
    }

    fun configureFabricResolutionCompatibility(project: Project, minecraftVersion: String) {
        if (minecraftVersion != "1.18" && minecraftVersion != "1.18.1") return

        project.configurations.configureEach {
            exclude(mapOf("group" to "net.fabricmc.fabric-api", "module" to "fabric-registry-sync-v0"))
            exclude(mapOf("group" to "net.fabricmc.fabric-api", "module" to "fabric-loot-api-v2"))
            resolutionStrategy.force("net.fabricmc.fabric-api:fabric-resource-loader-v0:0.4.12+e66b59e93a")
        }
    }

    fun addForgeLoaderLibraries(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
        minecraftVersion: String,
    ) {
        when {
            minecraftVersion in legacyForgeRuntimeModVersions -> {
                addForgeDependencyMods(project, context, catalog, "compileOnly", identity)
                project.dependencies.add("runtimeOnly", "org.slf4j:slf4j-simple:2.0.13")
            }
            VersionPolicy.usesLegacyForgePlugin(minecraftVersion) -> {
                addForgeDependencyMods(project, context, catalog, "compileOnly", identity)
                addForgeDependencyMods(project, context, catalog, "modRuntimeOnly", identity)
            }
            else -> addForgeDependencyMods(project, context, catalog, "implementation", identity)
        }
    }

    fun addNeoForgeLoaderLibraries(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
    ) {
        addOptional(project, context, catalog, "implementation", "amber-neoforge", identity)
        addOptional(project, context, catalog, "implementation", "konfig-neoforge", identity)
    }

    fun addTeaKitRuntime(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
        loader: LoaderId,
        minecraftVersion: String,
        strategy: TeaKitRuntimeStrategy,
    ) {
        val configuration = strategy.dependencyConfiguration ?: return
        if (identity.modId == "teakit") return
        val useTeaKit = project.providers.systemProperty("${identity.modId}.withTeaKit")
            .orElse(project.providers.gradleProperty("${identity.modId}.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get()
        if (!useTeaKit) return

        val teaKitVersion = context.versionOrNull(catalog, "teakit")
        if (teaKitVersion.isNullOrBlank() || teaKitVersion == "null") return
        addOptional(project, context, catalog, configuration, "teakit-${loader.id}", identity)
    }

    fun catalogModuleVersion(context: MultiloaderProjectContext, catalog: VersionCatalog, alias: String): String? =
        context.versionOrNull(catalog, alias)?.takeUnless { it == "null" }

    private fun addOptional(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        configuration: String,
        alias: String,
        identity: ProjectIdentity,
    ) {
        if (isSelfDependency(alias, identity.modId)) return
        val dependency = context.libraryOrNull(catalog, alias) ?: return
        project.dependencies.add(configuration, dependency)
    }

    private fun addForgeDependencyMods(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        configuration: String,
        identity: ProjectIdentity,
    ) {
        addOptional(project, context, catalog, configuration, "amber-forge", identity)
        addOptional(project, context, catalog, configuration, "konfig-forge", identity)
    }

    private fun isSelfDependency(alias: String, modId: String): Boolean =
        (modId == "amber" && alias.startsWith("amber")) ||
            (modId == "konfig" && alias.startsWith("konfig")) ||
            (modId == "teakit" && alias.startsWith("teakit"))

    private val fabric116DatagenVersions = setOf(
        "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
    )

    val legacyForgeRuntimeModVersions: Set<String> = setOf("1.17.1", "1.18", "1.18.1", "1.18.2")
}
