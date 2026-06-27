package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.publishing.MultiloaderPublishingExtension
import com.iamkaf.multiloader.support.MultiloaderTargetScope
import com.iamkaf.multiloader.translations.MultiloaderTranslationsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderRootPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("com.iamkaf.multiloader.root must be applied to the root project only.")
        }

        project.extensions.create("multiloaderStonecutter", MultiloaderStonecutterExtension::class.java, project)
        TeaKitRunPropertyForwarder.configure(project)

        if (RootVersionMatrix.hasVersionMatrix(project)) {
            applyStonecutterRootPlugin(project)
            return
        }

        applyFlatRootPlugin(project)
    }

    private fun applyFlatRootPlugin(project: Project) {
        applyCoordinates(project)
        FlatRootTasks.register(project)
        BuildGraphReporter.registerTasks(project)
    }

    private fun applyStonecutterRootPlugin(project: Project) {
        project.pluginManager.apply("com.iamkaf.multiloader.publishing")
        project.pluginManager.apply("com.iamkaf.multiloader.translations")
        StonecutterRootDefaults.configure(project)
        VersionMetadataTasks.register(project)
        applyCoordinates(project)

        val modId = requiredProperty(project, "mod.id")
        val versionDirs = RootVersionMatrix.versionDirectories(project)
        val targetScope = MultiloaderTargetScope.fromProject(
            project,
            RootVersionMatrix.enabledLoadersByVersion(versionDirs),
        )

        project.extensions.configure(MultiloaderTranslationsExtension::class.java) {
            projectSlug.set(project.providers.gradleProperty("mod.id"))
            outputDir.set(project.layout.projectDirectory.dir("common/src/main/resources/assets/$modId/lang"))
        }

        project.extensions.configure(MultiloaderPublishingExtension::class.java) {
            RootPublicationDefaults.configure(project, this, versionDirs, targetScope)
        }

        BuildGraphReporter.registerTasks(project)
    }

    private fun applyCoordinates(project: Project) {
        project.findProperty("project.group")?.let { project.group = it.toString() }
        project.findProperty("project.version")?.let { project.version = it.toString() }
    }

    private fun requiredProperty(project: Project, name: String): String {
        val value = project.findProperty(name)?.toString()
        if (value.isNullOrBlank()) {
            throw GradleException("Missing required property '$name'")
        }
        return value
    }
}
