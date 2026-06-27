package com.iamkaf.multiloader.support.adapters

import org.gradle.api.Project
import java.io.File

object ForgeMavenizerCacheGuard {
    fun deleteEmptyForgeArtifacts(project: Project, forgeArtifactVersion: String?) {
        if (forgeArtifactVersion.isNullOrBlank()) return

        val candidateDirs = listOf(
            File(
                project.gradle.gradleUserHomeDir,
                "caches/minecraftforge/forgegradle/mavenizer/caches/maven/forge/net/minecraftforge/forge/$forgeArtifactVersion",
            ),
            File(
                project.gradle.gradleUserHomeDir,
                "caches/minecraftforge/forgegradle/mavenizer/caches/forge/net/minecraftforge/forge/$forgeArtifactVersion",
            ),
            File(
                project.rootProject.projectDir,
                ".gradle/mavenizer/repo/net/minecraftforge/forge/$forgeArtifactVersion",
            ),
        )

        candidateDirs.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension == "jar" && it.length() == 0L }
            .forEach { file ->
                if (file.delete()) {
                    project.logger.lifecycle("[ForgeMavenizer] Deleted empty cached Forge artifact: ${file.absolutePath}")
                }
            }
    }
}
