package com.iamkaf.multiloader.translations

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@DisableCachingByDefault(because = "Downloads remote translation exports and always refreshes its outputs.")
abstract class DownloadTranslationsTask : DefaultTask() {
    @get:Input
    abstract val projectSlug: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val baseUrl: Property<String>

    @get:Internal
    abstract val token: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun downloadTranslations() {
        val slug = requiredText(projectSlug, "multiloaderTranslations.projectSlug")
        if (!outputDir.isPresent) {
            throw GradleException("multiloaderTranslations.outputDir must be configured.")
        }

        val outputPath = outputDir.get().asFile.toPath()
        val client = I18nExportClient(requiredText(baseUrl, "multiloaderTranslations.baseUrl"), token.orNull)
        val projectIndex = client.fetchProjectIndex(slug)

        Files.createDirectories(outputPath)

        val sourceLocale = projectIndex.defaultLocale ?: projectIndex.locales.firstOrNull { it.source }?.locale
        val localesToDownload = projectIndex.locales
            .mapNotNull { it.locale }
            .filter { locale -> locale != "en_us" && locale != sourceLocale }
            .distinct()
            .sorted()

        if (localesToDownload.isEmpty()) {
            logger.lifecycle("[Translations] No non-source locales available for '{}'.", slug)
            return
        }

        localesToDownload.forEach { locale ->
            val export = client.fetchLocaleExport(slug, locale)
            val destination = outputPath.resolve("$locale.json")
            writeAtomically(destination, export.rawBody)
            logger.lifecycle("[Translations] Wrote {}", project.relativePath(destination.toFile()))
        }
    }

    private companion object {
        fun requiredText(property: Property<String>, propertyName: String): String {
            if (!property.isPresent) {
                throw GradleException("$propertyName must be configured.")
            }

            val value = property.get().trim()
            if (value.isBlank()) {
                throw GradleException("$propertyName must not be blank.")
            }
            return value
        }

        fun writeAtomically(destination: Path, contents: String) {
            Files.createDirectories(destination.parent)
            val tempFile = Files.createTempFile(destination.parent, destination.fileName.toString(), ".tmp")

            try {
                Files.writeString(tempFile, contents, StandardCharsets.UTF_8)
                try {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    }
}
