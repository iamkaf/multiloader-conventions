package com.iamkaf.multiloader.translations

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

abstract class DownloadTranslationsTask extends DefaultTask {

    @Input
    abstract Property<String> getProjectSlug()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @Input
    abstract Property<String> getBaseUrl()

    @Internal
    abstract Property<String> getToken()

    DownloadTranslationsTask() {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    void downloadTranslations() {
        def slug = requiredText(projectSlug, 'multiloaderTranslations.projectSlug')
        if (!outputDir.present) {
            throw new GradleException('multiloaderTranslations.outputDir must be configured.')
        }

        def outputPath = outputDir.get().asFile.toPath()
        def client = new I18nExportClient(requiredText(baseUrl, 'multiloaderTranslations.baseUrl'), token.orNull)
        def projectIndex = client.fetchProjectIndex(slug)

        Files.createDirectories(outputPath)

        def sourceLocale = projectIndex.defaultLocale ?: projectIndex.locales.find { it.source }?.locale
        def localesToDownload = projectIndex.locales
            .collect { it.locale }
            .findAll { locale -> locale != null && locale != 'en_us' && locale != sourceLocale }
            .unique()
            .sort()

        if (localesToDownload.isEmpty()) {
            logger.lifecycle("[Translations] No non-source locales available for '{}'.", slug)
            return
        }

        localesToDownload.each { locale ->
            def export = client.fetchLocaleExport(slug, locale)
            def destination = outputPath.resolve("${locale}.json")
            DownloadTranslationsTask.writeAtomically(destination, export.rawBody)
            logger.lifecycle("[Translations] Wrote {}", project.relativePath(destination.toFile()))
        }
    }

    private static String requiredText(Property<String> property, String propertyName) {
        if (!property.present) {
            throw new GradleException("${propertyName} must be configured.")
        }

        def value = property.get().trim()
        if (value.isBlank()) {
            throw new GradleException("${propertyName} must not be blank.")
        }
        value
    }

    private static void writeAtomically(Path destination, String contents) {
        Files.createDirectories(destination.parent)
        def tempFile = Files.createTempFile(destination.parent, destination.fileName.toString(), '.tmp')

        try {
            Files.writeString(tempFile, contents, StandardCharsets.UTF_8)
            try {
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
