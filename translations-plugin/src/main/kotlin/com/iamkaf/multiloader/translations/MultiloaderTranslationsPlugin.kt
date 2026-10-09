package com.iamkaf.multiloader.translations

import com.iamkaf.multiloader.support.ConsumerDslPolicy
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderTranslationsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("com.iamkaf.multiloader.translations must be applied to the root project only.")
        }
        ConsumerDslPolicy.requireKotlinDsl(project)

        val extension = project.extensions.create(
            "multiloaderTranslations",
            MultiloaderTranslationsExtension::class.java,
        )
        extension.baseUrl.convention("https://kaf.sh")

        project.tasks.register("downloadTranslations", DownloadTranslationsTask::class.java) {
            group = "translations"
            description = "Downloads approved translations from kaf.sh Translate."
            projectSlug.convention(extension.projectSlug)
            outputDir.convention(extension.outputDir)
            baseUrl.convention(extension.baseUrl)
        }
    }
}
