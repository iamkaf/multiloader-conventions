package com.iamkaf.multiloader.publishing

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

internal object PublishingTaskRegistrar {
    fun register(project: Project, extension: MultiloaderPublishingExtension) {
        val registeredPublicationSuffixes = mutableSetOf<String>()

        val assembleAll = project.tasks.register("publishingAssemble") {
            group = "publishing"
            description = "Stage all enabled artifacts for the release manifest."
        }

        val writeManifest = project.tasks.register("writeReleaseManifest") {
            group = "publishing"
            description = "Writes build/release/release-manifest.json for the enabled artifacts."
        }

        fun registerPublicationTaskShell(publicationName: String) {
            val taskSuffix = PublicationPlanner.taskSuffix(publicationName)
            if (!registeredPublicationSuffixes.add(taskSuffix)) return

            val assembleTask = project.tasks.register("publishingAssemble$taskSuffix") {
                group = "publishing"
                description = "Stage the $publicationName artifact for the release manifest."
            }
            assembleAll.configure { dependsOn(assembleTask) }
        }

        listOf("fabric", "forge", "neoforge").forEach(::registerPublicationTaskShell)
        extension.getPublications().configureEach {
            registerPublicationTaskShell(getName())
        }

        project.gradle.projectsEvaluated {
            val plannedSpecs = mutableListOf<PublicationSpec>()
            PublicationPlanner.configuredPublications(project, extension).forEach { publicationConfig ->
                registerPublicationTaskShell(publicationConfig.name)
                val taskSuffix = PublicationPlanner.taskSuffix(publicationConfig.name)

                if (!publicationConfig.enabled) {
                    configureDisabledPublicationTasks(project, taskSuffix)
                    return@forEach
                }

                if (PublicationPlanner.awaitsConfiguration(project, publicationConfig)) {
                    configureUnconfiguredPublicationTasks(project, taskSuffix, publicationConfig.projectPath)
                    return@forEach
                }

                val spec = PublicationPlanner.plan(project, publicationConfig)
                val assembleTask = configureAssembleTask(project, extension, publicationConfig, spec)
                plannedSpecs.add(spec)
                writeManifest.configure { dependsOn(assembleTask) }
            }
            configureManifestTask(project, extension, writeManifest, plannedSpecs)
        }
    }

    private fun configureManifestTask(
        project: Project,
        extension: MultiloaderPublishingExtension,
        writeManifest: TaskProvider<Task>,
        specs: List<PublicationSpec>,
    ) {
        val output = project.layout.buildDirectory.file("release/release-manifest.json")
        writeManifest.configure {
            outputs.file(output)
            doFirst {
                if (specs.isEmpty()) {
                    throw IllegalStateException("[Publishing] No enabled publications to put in the release manifest")
                }
            }
            doLast {
                val manifest = ReleaseManifestPlanner.plan(project, extension, specs)
                val file = output.get().asFile
                file.parentFile.mkdirs()
                file.writeText(ReleaseManifestWriter.toJson(manifest))
                project.logger.lifecycle("[Publishing] Wrote ${project.relativePath(file)} (${specs.size} files)")
            }
        }
    }

    private fun configureAssembleTask(
        project: Project,
        extension: MultiloaderPublishingExtension,
        publicationConfig: PublicationConfig,
        spec: PublicationSpec,
    ): TaskProvider<Task> =
        project.tasks.named("publishingAssemble${spec.taskSuffix}") {
            publicationConfig.buildTasks.forEach { taskName ->
                val extraTask = spec.project.tasks.findByName(taskName)
                if (extraTask != null) {
                    dependsOn(extraTask)
                }
            }
            dependsOn(spec.jarOutput.task)

            doFirst {
                project.logger.lifecycle(
                    "[Publishing] Assemble starting for ${publicationConfig.name} " +
                        "(releaseType=${extension.getConfig().releaseType.get()}, " +
                        "loaders=${spec.loaders}, gameVersions=${spec.gameVersions})",
                )
            }

            doLast {
                ArtifactStager.stage(project, extension, spec)
            }
        }

    private fun configureUnconfiguredPublicationTasks(project: Project, taskSuffix: String, projectPath: String) {
        project.tasks.named("publishingAssemble$taskSuffix") {
            doFirst {
                throw IllegalStateException(
                    "[Publishing] $projectPath was not configured in this build; run without --configure-on-demand",
                )
            }
        }
    }

    private fun configureDisabledPublicationTasks(project: Project, taskSuffix: String) {
        project.tasks.named("publishingAssemble$taskSuffix") {
            onlyIf { false }
        }
    }
}
