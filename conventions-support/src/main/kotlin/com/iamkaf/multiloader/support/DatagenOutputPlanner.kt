package com.iamkaf.multiloader.support

import org.gradle.api.Project
import java.io.File

data class StonecutterDatagenLane(
    val minecraftVersion: String,
    val rootName: String,
    val directory: File,
)

object DatagenOutputPlanner {
    @JvmStatic
    fun commonDatagenOutputDirectory(project: Project, minecraftVersion: String, usesStonecutter: Boolean): File =
        if (usesStonecutter) {
            commonStonecutterLane(project, minecraftVersion).directory
        } else {
            project.rootProject.file("common/src/main/generated")
        }

    @JvmStatic
    fun commonGeneratedResourcesRoot(project: Project, minecraftVersion: String, usesStonecutter: Boolean): File =
        if (usesStonecutter) {
            commonStonecutterLane(project, minecraftVersion).directory
        } else {
            project.file("src/main/generated")
        }

    @JvmStatic
    fun loaderGeneratedResourcesRoot(project: Project, loader: String, minecraftVersion: String): File =
        project.rootProject.file("versions/$minecraftVersion/$loader/src/main/generated")

    @JvmStatic
    fun commonStonecutterLane(project: Project, minecraftVersion: String): StonecutterDatagenLane =
        stonecutterLane(project, minecraftVersion, "common")

    @JvmStatic
    fun stonecutterLane(project: Project, minecraftVersion: String, rootName: String): StonecutterDatagenLane =
        StonecutterDatagenLane(
            minecraftVersion = minecraftVersion,
            rootName = rootName,
            directory = project.rootProject.file("versions/$minecraftVersion/$rootName/src/main/generated"),
        )
}
