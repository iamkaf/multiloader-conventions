package com.iamkaf.multiloader.root

internal object HorizontalArtifactNaming {
    fun relativePath(minecraftVersion: String, modId: String, projectVersion: String): String =
        "libs/horizontal/$minecraftVersion/$modId-multiloader-$projectVersion.jar"

    fun obsoleteRelativePaths(minecraftVersion: String, modId: String, projectVersion: String): List<String> = listOf(
        "libs/horizontal/$minecraftVersion/$modId-$projectVersion.jar",
        "libs/horizontal/$minecraftVersion/$modId-$projectVersion-multiloader.jar",
    )
}
