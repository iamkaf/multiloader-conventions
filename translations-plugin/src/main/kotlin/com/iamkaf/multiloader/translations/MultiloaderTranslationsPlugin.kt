package com.iamkaf.multiloader.translations

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderTranslationsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("com.iamkaf.multiloader.translations must be applied to the root project only.")
        }

        val extension = project.extensions.create(
            "multiloaderTranslations",
            MultiloaderTranslationsExtension::class.java,
        )
        extension.baseUrl.convention("https://i18n.kaf.sh")
        extension.token.convention(
            project.providers.gradleProperty("translations.token")
                .orElse(project.providers.environmentVariable("I18N_TOKEN")),
        )

        project.tasks.register("downloadTranslations", DownloadTranslationsTask::class.java) {
            group = "translations"
            description = "Downloads approved non-en_us translations from the configured i18n export project."
            projectSlug.convention(extension.projectSlug)
            outputDir.convention(extension.outputDir)
            baseUrl.convention(extension.baseUrl)
            token.convention(extension.token)
        }
    }
}
