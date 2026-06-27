package com.iamkaf.multiloader.settings

import org.gradle.api.initialization.Settings
import java.io.File

internal object ConsumerDslPolicy {
    private val ignoredDirectoryNames = setOf(".git", ".gradle", "build")
    private val groovyGradleScriptNames = setOf("settings.gradle", "build.gradle")

    fun requireKotlinDsl(settings: Settings) {
        val offendingFiles = groovyGradleScripts(settings.settingsDir)
        if (offendingFiles.isEmpty()) return

        val relativeFiles = offendingFiles.joinToString(", ") { file ->
            file.relativeTo(settings.settingsDir).invariantSeparatorsPath
        }
        throw IllegalStateException(
            "Multiloader Conventions 3.0 requires Kotlin DSL build scripts. " +
                "Rename these files to .gradle.kts or remove them: $relativeFiles",
        )
    }

    private fun groovyGradleScripts(settingsDir: File): List<File> =
        settingsDir.walkTopDown()
            .onEnter { directory -> directory == settingsDir || directory.name !in ignoredDirectoryNames }
            .filter { file -> file.isFile && file.name in groovyGradleScriptNames }
            .sortedBy { file -> file.relativeTo(settingsDir).invariantSeparatorsPath }
            .toList()
}
