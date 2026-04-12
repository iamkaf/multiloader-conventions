package com.iamkaf.multiloader.translations

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderTranslationsPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (project != project.rootProject) {
            throw new GradleException('com.iamkaf.multiloader.translations must be applied to the root project only.')
        }

        def extension = project.extensions.create('multiloaderTranslations', MultiloaderTranslationsExtension)
        extension.baseUrl.convention('https://i18n.kaf.sh')
        extension.token.convention(
            project.providers.gradleProperty('translations.token')
                .orElse(project.providers.environmentVariable('I18N_TOKEN'))
        )

        project.tasks.register('downloadTranslations', DownloadTranslationsTask) { task ->
            task.group = 'translations'
            task.description = 'Downloads approved non-en_us translations from the configured i18n export project.'
            task.projectSlug.convention(extension.projectSlug)
            task.outputDir.convention(extension.outputDir)
            task.baseUrl.convention(extension.baseUrl)
            task.token.convention(extension.token)
        }
    }
}
