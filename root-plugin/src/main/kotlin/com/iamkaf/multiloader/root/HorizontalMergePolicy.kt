package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.GradleException

enum class HorizontalMergeTier {
    STABLE,
    UNSTABLE_RELOCATED,
}

object HorizontalMergePolicy {
    private val provenUnstableVersions = setOf("1.21.1", "1.21.11")

    fun tier(version: String): HorizontalMergeTier? =
        when {
            VersionPolicy.isMinecraftVersionAtLeast(version, "26.1") -> HorizontalMergeTier.STABLE
            version in provenUnstableVersions -> HorizontalMergeTier.UNSTABLE_RELOCATED
            else -> null
        }

    fun requireSupported(version: String, allowedUnstableVersions: Set<String>): HorizontalMergeTier =
        when (val tier = tier(version)) {
            HorizontalMergeTier.STABLE -> tier
            HorizontalMergeTier.UNSTABLE_RELOCATED -> if (version in allowedUnstableVersions) {
                tier
            } else {
                throw GradleException(
                    """
                    Cannot create a horizontal multi-loader jar for Minecraft $version yet.

                    This version is in the experimental relocated tier. Forgix can merge its loader jars, but it may
                    rename common classes, mixin configs, assets, and loader metadata. That can break addons, mixins,
                    and other mods that expect the original binary names.

                    Your ordinary Fabric, Forge, and NeoForge jars are unaffected and remain the recommended artifacts.
                    If you have tested the merged jar and accept the compatibility risk, opt in explicitly:

                        multiloaderArtifacts {
                            horizontalMerge {
                                acknowledgeUnsafeVersion("$version")
                            }
                        }

                    The acknowledgement is intentionally required once per Minecraft version.
                    """.trimIndent(),
                )
            }
            null -> throw GradleException(
                """
                Cannot create a horizontal multi-loader jar for Minecraft $version.

                Horizontal merging is deliberately restricted to version lines whose loader jars have a known-safe
                combination strategy. Minecraft 26.1 and newer are supported as the stable tier. The only older,
                explicitly testable experimental versions are ${provenUnstableVersions.sorted().joinToString(", ")}.

                Nothing is wrong with the normal build: its separate Fabric, Forge, and NeoForge jars can still be
                built and published as usual. Keep those loader-specific artifacts for Minecraft $version instead of
                producing a `-multiloader.jar` whose metadata or class layout may be invalid at runtime.

                If support for this Minecraft version is added after compatibility research and validation, the
                horizontal merge policy must be updated in multiloader-conventions first. There is no generic unsafe
                override for unknown versions, because silently producing a plausible-looking broken jar is worse than
                stopping here with a clear error.
                """.trimIndent(),
            )
        }

    fun requireKnownUnstableVersions(versions: Set<String>) {
        val unknown = versions - provenUnstableVersions
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "allowUnstableVersions contains versions without a proven horizontal-merge tier: " +
                    unknown.sorted().joinToString(", "),
            )
        }
    }
}
