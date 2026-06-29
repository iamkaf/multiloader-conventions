package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import java.io.File

object ConsumerDslPolicy {
    private val ignoredDirectoryNames = setOf(".git", ".gradle", "build")

    fun requireKotlinDsl(settings: Settings) {
        requireKotlinDsl(settings.settingsDir)
    }

    fun requireKotlinDsl(project: Project) {
        requireKotlinDsl(project.rootProject.projectDir)
    }

    private fun requireKotlinDsl(rootDir: File) {
        val offendingFiles = groovyGradleScripts(rootDir)
        if (offendingFiles.isEmpty()) return

        val relativeFiles = offendingFiles.joinToString(", ") { file ->
            file.relativeTo(rootDir).invariantSeparatorsPath
        }
        throw GradleException(
            "Multiloader Conventions 3.0 requires Kotlin DSL build scripts. " +
                "Rename these files to .gradle.kts or remove them: $relativeFiles",
        )
    }

    private fun groovyGradleScripts(rootDir: File): List<File> =
        rootDir.walkTopDown()
            .onEnter { directory -> directory == rootDir || directory.name !in ignoredDirectoryNames }
            .filter { file -> file.isFile && file.extension == "gradle" }
            .sortedBy { file -> file.relativeTo(rootDir).invariantSeparatorsPath }
            .toList()
}
