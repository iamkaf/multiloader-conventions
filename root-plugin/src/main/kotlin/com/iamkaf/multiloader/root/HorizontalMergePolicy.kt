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
                    "Horizontal merge for Minecraft $version may relocate common classes and break addon/mixin binary names. " +
                        "Acknowledge this explicitly with acknowledgeUnsafeVersion(\"$version\").",
                )
            }
            null -> throw GradleException(
                "Horizontal merge is unsupported for Minecraft $version. " +
                    "The supported stable tier starts at 26.1; the only acknowledged experimental versions are " +
                    provenUnstableVersions.sorted().joinToString(", ") + ".",
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
