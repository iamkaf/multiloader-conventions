package com.iamkaf.multiloader.publishing

import com.iamkaf.multiloader.support.ConsumerDslPolicy
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderPublishingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw IllegalStateException("com.iamkaf.multiloader.publishing must be applied to the root project only.")
        }
        ConsumerDslPolicy.requireKotlinDsl(project)

        val extension = project.extensions.create(
            EXTENSION_NAME,
            MultiloaderPublishingExtension::class.java,
            project.objects,
        )
        PublishingDefaults.configure(project, extension)
        PublishingTaskRegistrar.register(project, extension)
    }

    companion object {
        const val EXTENSION_NAME = "multiloaderPublishing"
    }
}
