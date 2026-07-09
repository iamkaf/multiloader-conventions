package com.iamkaf.multiloader.root

internal object HorizontalArtifactNaming {
    fun relativePath(minecraftVersion: String, modId: String, projectVersion: String): String =
        "libs/horizontal/$minecraftVersion/$modId-$projectVersion-multiloader.jar"

    fun legacyRelativePath(minecraftVersion: String, modId: String, projectVersion: String): String =
        "libs/horizontal/$minecraftVersion/$modId-$projectVersion.jar"
}
