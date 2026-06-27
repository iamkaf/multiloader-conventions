package com.iamkaf.multiloader.support

import java.util.Properties

enum class LoaderId(val id: String) {
    FABRIC("fabric"),
    FORGE("forge"),
    NEOFORGE("neoforge");

    companion object {
        private val byId = entries.associateBy { it.id }

        fun parse(id: String): LoaderId? = byId[id.trim()]
    }
}

enum class CommonToolchainStrategy {
    FABRIC_LOOM,
    LEGACY_FORGE,
    NEOFORM,
}

enum class FabricDependencyStrategy {
    MODERN_UNOBFUSCATED,
    OBFUSCATED_LOOM,
}

enum class ForgeRunStrategy {
    MAINSTREAM_FORGE_GRADLE,
    LEGACY_MODDEV,
    LEGACY_USERDEV_1165,
    UNSUPPORTED,
}

enum class NeoForgeToolchainStrategy {
    MODDEV,
    NEOGRADLE_USERDEV,
}

enum class TeaKitRuntimeStrategy(val dependencyConfiguration: String?) {
    DISABLED_FOR_LEGACY_FORGE(null),
    FABRIC_LOCAL_RUNTIME("modLocalRuntime"),
    RUNTIME_ONLY("runtimeOnly"),
    MOD_RUNTIME_ONLY("modRuntimeOnly"),
}

enum class PublicationArtifactStrategy(val artifactTask: String, val fallbackArtifactTask: String?, val buildTasks: List<String>) {
    JAR("jar", null, emptyList()),
    FABRIC_REMAP_JAR("remapJar", "jar", emptyList()),
    FORGE_REOBF_JAR("jar", null, listOf("reobfJar")),
}

data class VersionMetadata(
    val minecraftVersion: String,
    val enabledLoaders: List<LoaderId>,
    val javaVersion: Int,
    val buildJavaVersion: Int,
    val minecraftRange: String,
    val fabricRange: String,
    val forgeLoaderRange: String?,
    val neoForgeLoaderRange: String?,
    val mixinCompatCommon: String,
    val mixinCompatFabric: String,
    val mixinCompatForge: String,
    val mixinCompatNeoForge: String,
    val catalogName: String,
    val catalogCoordinate: String,
    val commonToolchainStrategy: CommonToolchainStrategy,
    val fabricDependencyStrategy: FabricDependencyStrategy,
    val forgeRunStrategy: ForgeRunStrategy,
    val neoForgeToolchainStrategy: NeoForgeToolchainStrategy,
) {
    fun asProperties(defaultProjectVersion: String = "11.0.0+$minecraftVersion"): Properties =
        Properties().also { props ->
            props.setProperty("project.minecraft", minecraftVersion)
            props.setProperty("project.version", defaultProjectVersion)
            props.setProperty("project.java", javaVersion.toString())
            props.setProperty("project.build-java", buildJavaVersion.toString())
            props.setProperty("project.enabled-loaders", enabledLoaders.joinToString(",") { it.id })
            props.setProperty("project.catalog-name", catalogName)
            props.setProperty("project.catalog-coordinate", catalogCoordinate)
            props.setProperty("mod.minecraft-range", minecraftRange)
            props.setProperty("mod.fabric-range", fabricRange)
            props.setProperty("mixin.compat.common", mixinCompatCommon)
            props.setProperty("mixin.compat.fabric", mixinCompatFabric)
            props.setProperty("mixin.compat.forge", mixinCompatForge)
            props.setProperty("mixin.compat.neoforge", mixinCompatNeoForge)
            forgeLoaderRange?.let { props.setProperty("mod.forge-loader-range", it) }
            neoForgeLoaderRange?.let { props.setProperty("mod.neoforge-loader-range", it) }
        }
}

object VersionPolicy {
    val supportedReleaseVersions: List<String> = listOf(
        "1.14.4",
        "1.15", "1.15.1", "1.15.2",
        "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
        "1.17", "1.17.1",
        "1.18", "1.18.1", "1.18.2",
        "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
        "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
        "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
        "26.1", "26.1.1", "26.1.2", "26.2",
    )

    private val legacyFabricOnly = setOf(
        "1.14.4",
        "1.15", "1.15.1", "1.15.2",
        "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
        "1.17",
    )

    private val forgeLoaderRanges = linkedMapOf(
        "1.16.4" to "[36,)", "1.16.5" to "[36,)", "1.17.1" to "[37,)",
        "1.18" to "[38,)", "1.18.1" to "[39,)", "1.18.2" to "[40,)",
        "1.19" to "[41,)", "1.19.1" to "[42,)", "1.19.2" to "[43,)", "1.19.3" to "[44,)",
        "1.19.4" to "[45,)",
        "1.20" to "[46,)", "1.20.1" to "[47,)", "1.20.2" to "[48,)", "1.20.3" to "[49,)",
        "1.20.4" to "[49,)", "1.20.6" to "[50,)",
        "1.21" to "[51,)", "1.21.1" to "[52,)", "1.21.3" to "[53,)", "1.21.4" to "[54,)",
        "1.21.5" to "[55,)", "1.21.6" to "[56,)", "1.21.7" to "[57,)", "1.21.8" to "[58,)",
        "1.21.9" to "[59,)", "1.21.10" to "[60,)", "1.21.11" to "[61,)",
        "26.1" to "[62,)", "26.1.1" to "[62,)", "26.1.2" to "[62,)", "26.2" to "[65,)",
    )

    fun metadata(version: String): VersionMetadata {
        val loaders = enabledLoaders(version)
        val java = javaVersion(version)
        val commonMixinCompat = mixinCompat(version)
        return VersionMetadata(
            minecraftVersion = version,
            enabledLoaders = loaders,
            javaVersion = java,
            buildJavaVersion = buildJavaVersion(version),
            minecraftRange = "[$version, ${nextMinecraftUpperBound(version)})",
            fabricRange = ">=$version",
            forgeLoaderRange = forgeLoaderRange(version),
            neoForgeLoaderRange = if (LoaderId.NEOFORGE in loaders) "[4,)" else null,
            mixinCompatCommon = commonMixinCompat,
            mixinCompatFabric = commonMixinCompat,
            mixinCompatForge = forgeMixinCompat(version),
            mixinCompatNeoForge = commonMixinCompat,
            catalogName = catalogName(version),
            catalogCoordinate = catalogCoordinate(version),
            commonToolchainStrategy = commonToolchainStrategy(version),
            fabricDependencyStrategy = fabricDependencyStrategy(version),
            forgeRunStrategy = forgeRunStrategy(version),
            neoForgeToolchainStrategy = neoForgeToolchainStrategy(version),
        )
    }

    fun metadataProperties(version: String): Properties = metadata(version).asProperties()

    fun enabledLoaders(version: String): List<LoaderId> =
        when {
            version in legacyFabricOnly || version == "1.20.5" -> listOf(LoaderId.FABRIC)
            version == "1.21.2" -> listOf(LoaderId.FABRIC, LoaderId.NEOFORGE)
            version == "1.21.1" || version.startsWith("26.") ||
                (version.startsWith("1.21.") && version != "1.21.2") ->
                listOf(LoaderId.FABRIC, LoaderId.FORGE, LoaderId.NEOFORGE)
            else -> listOf(LoaderId.FABRIC, LoaderId.FORGE)
        }

    fun enabledLoaderIds(version: String): String = enabledLoaders(version).joinToString(",") { it.id }

    fun javaVersion(version: String): Int =
        when {
            version.startsWith("26.") -> 25
            version.startsWith("1.14") || version.startsWith("1.15") || version.startsWith("1.16") ||
                version == "1.17" || version == "1.17.1" -> 16
            version == "1.20.5" || version == "1.20.6" || version.startsWith("1.21") -> 21
            else -> 17
        }

    fun buildJavaVersion(version: String): Int =
        when {
            version.startsWith("26.") -> 25
            else -> 21
        }

    fun catalogName(minecraftVersion: String): String =
        "libsMc${minecraftVersion.replace(".", "").replace("-", "")}"

    fun catalogCoordinate(minecraftVersion: String): String =
        "com.iamkaf.platform:mc-$minecraftVersion:$minecraftVersion-SNAPSHOT"

    fun resourcePackFormat(version: String): String =
        when {
            version.startsWith("1.21") -> "81"
            version.startsWith("1.20") -> "15"
            else -> "8"
        }

    fun resourcePackMinMaxSnippet(version: String): String =
        if (version.startsWith("1.21")) {
            ",\n    \"min_format\": 81,\n    \"max_format\": 81"
        } else {
            ""
        }

    fun forgeLoaderRange(version: String): String? = forgeLoaderRanges[version]

    fun useUnobfuscatedMinecraft(version: String): Boolean = version.startsWith("26.")

    fun usesLegacyFabricApiModules(version: String): Boolean = version == "1.16" || version == "1.16.1"

    fun commonToolchainStrategy(version: String): CommonToolchainStrategy {
        val useFabricLoomCommon = version == "1.16.5" ||
            version.startsWith("1.16.") ||
            version == "1.16" ||
            version.startsWith("1.15.") ||
            version == "1.15" ||
            version.startsWith("1.14.")
        return if (useFabricLoomCommon) CommonToolchainStrategy.FABRIC_LOOM else CommonToolchainStrategy.NEOFORM
    }

    fun commonToolchainStrategy(version: String, hasNeoForm: Boolean): CommonToolchainStrategy =
        when (commonToolchainStrategy(version)) {
            CommonToolchainStrategy.FABRIC_LOOM -> CommonToolchainStrategy.FABRIC_LOOM
            CommonToolchainStrategy.NEOFORM -> if (hasNeoForm) CommonToolchainStrategy.NEOFORM else CommonToolchainStrategy.LEGACY_FORGE
            CommonToolchainStrategy.LEGACY_FORGE -> CommonToolchainStrategy.LEGACY_FORGE
        }

    fun fabricDependencyStrategy(version: String): FabricDependencyStrategy =
        if (useUnobfuscatedMinecraft(version)) FabricDependencyStrategy.MODERN_UNOBFUSCATED
        else FabricDependencyStrategy.OBFUSCATED_LOOM

    fun fabricTeaKitRuntimeStrategy(version: String): TeaKitRuntimeStrategy =
        if (version.startsWith("1.")) TeaKitRuntimeStrategy.FABRIC_LOCAL_RUNTIME else TeaKitRuntimeStrategy.RUNTIME_ONLY

    fun forgeRunStrategy(version: String): ForgeRunStrategy =
        when {
            version == "1.16.5" -> ForgeRunStrategy.LEGACY_USERDEV_1165
            version.startsWith("1.14") || version.startsWith("1.15") ||
                version == "1.16" || (version.startsWith("1.16.") && version != "1.16.5") ->
                ForgeRunStrategy.UNSUPPORTED
            usesLegacyForgePlugin(version) -> ForgeRunStrategy.LEGACY_MODDEV
            else -> ForgeRunStrategy.MAINSTREAM_FORGE_GRADLE
        }

    fun isForgeConventionSupported(version: String): Boolean =
        forgeRunStrategy(version) != ForgeRunStrategy.UNSUPPORTED

    fun forgeTeaKitRuntimeStrategy(version: String, legacyForgePlugin: Boolean): TeaKitRuntimeStrategy =
        if (legacyForgePlugin) {
            if (version in setOf("1.16.5", "1.17.1", "1.18", "1.18.1", "1.18.2")) {
                TeaKitRuntimeStrategy.DISABLED_FOR_LEGACY_FORGE
            } else {
                TeaKitRuntimeStrategy.MOD_RUNTIME_ONLY
            }
        } else {
            TeaKitRuntimeStrategy.RUNTIME_ONLY
        }

    fun neoForgeToolchainStrategy(version: String): NeoForgeToolchainStrategy =
        if (version in setOf("1.20.2", "1.20.3", "1.20.4")) {
            NeoForgeToolchainStrategy.NEOGRADLE_USERDEV
        } else {
            NeoForgeToolchainStrategy.MODDEV
        }

    fun fabricPublicationArtifact(version: String): PublicationArtifactStrategy =
        if (useUnobfuscatedMinecraft(version)) PublicationArtifactStrategy.JAR
        else PublicationArtifactStrategy.FABRIC_REMAP_JAR

    fun forgePublicationArtifact(): PublicationArtifactStrategy = PublicationArtifactStrategy.FORGE_REOBF_JAR

    fun neoForgePublicationArtifact(): PublicationArtifactStrategy = PublicationArtifactStrategy.JAR

    fun usesLegacyForgePlugin(version: String?): Boolean {
        if (version == null || !version.startsWith("1.")) return false
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return minor >= 17 && (minor < 20 || (minor == 20 && patch <= 1))
    }

    fun nextMinecraftUpperBound(version: String): String {
        if (version.startsWith("26.")) return "27"
        val parts = version.split(".")
        if (parts.size < 2 || parts[0] != "1") return version
        return "1.${parts[1].toInt() + 1}"
    }

    private fun mixinCompat(version: String): String =
        when {
            version.startsWith("1.14") || version.startsWith("1.15") || version.startsWith("1.16") ||
                version == "1.17" || version == "1.17.1" -> "JAVA_16"
            version.startsWith("1.18") || version.startsWith("1.19") ||
                version == "1.20" || version == "1.20.1" || version == "1.20.2" ||
                version == "1.20.3" || version == "1.20.4" -> "JAVA_17"
            else -> "JAVA_21"
        }

    private fun forgeMixinCompat(version: String): String =
        when (version) {
            "1.20.6" -> "JAVA_17"
            else -> mixinCompat(version)
        }
}
