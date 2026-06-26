package com.iamkaf.multiloader.publishing

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

internal object PublishingTaskRegistrar {
    fun register(project: Project, extension: MultiloaderPublishingExtension) {
        val registeredPublicationSuffixes = mutableSetOf<String>()

        val assembleAll = project.tasks.register("publishingAssemble") {
            group = "publishing"
            description = "Aggregate all enabled artifacts for release publishing."
        }

        val publishCurseforgeAll = project.tasks.register("publishCurseforge") {
            group = "publishing"
            description = "Upload all enabled artifacts to CurseForge."
        }

        val publishModrinthAll = project.tasks.register("publishModrinth") {
            group = "publishing"
            description = "Upload all enabled artifacts to Modrinth."
        }

        val publishAll = project.tasks.register("publishMod") {
            group = "publishing"
            description = "Upload all enabled artifacts to configured platforms."
            dependsOn(publishCurseforgeAll, publishModrinthAll)
        }

        project.tasks.register("publishingPublish") {
            group = "publishing"
            description = "Compatibility aggregate for publishMod."
            dependsOn(publishAll)
        }

        project.tasks.register("publishingRelease") {
            group = "publishing"
            description = "Compatibility aggregate for publishMod."
            dependsOn(publishAll)
        }

        fun registerPublicationTaskShell(publicationName: String) {
            val taskSuffix = PublicationPlanner.taskSuffix(publicationName)
            if (!registeredPublicationSuffixes.add(taskSuffix)) return

            val assembleTask = project.tasks.register("publishingAssemble$taskSuffix") {
                group = "publishing"
                description = "Aggregate the $publicationName artifact for release publishing."
            }
            assembleAll.configure { dependsOn(assembleTask) }

            val curseTask = project.tasks.register("publishCurseforge$taskSuffix") {
                group = "publishing"
                description = "Upload the $publicationName artifact to CurseForge."
                dependsOn(assembleTask)
            }
            publishCurseforgeAll.configure { dependsOn(curseTask) }

            val modrinthTask = project.tasks.register("publishModrinth$taskSuffix") {
                group = "publishing"
                description = "Upload the $publicationName artifact to Modrinth."
                dependsOn(assembleTask)
            }
            publishModrinthAll.configure { dependsOn(modrinthTask) }
        }

        listOf("fabric", "forge", "neoforge").forEach(::registerPublicationTaskShell)
        extension.getPublications().configureEach {
            registerPublicationTaskShell(getName())
        }

        project.gradle.projectsEvaluated {
            PublicationPlanner.configuredPublications(project, extension).forEach { publicationConfig ->
                registerPublicationTaskShell(publicationConfig.name)
                val taskSuffix = PublicationPlanner.taskSuffix(publicationConfig.name)

                if (!publicationConfig.enabled) {
                    configureDisabledPublicationTasks(project, taskSuffix)
                    return@forEach
                }

                val spec = PublicationPlanner.plan(project, publicationConfig)
                val assembleTask = configureAssembleTask(project, extension, publicationConfig, spec)
                configureCurseForgeTask(project, extension, spec, assembleTask)
                configureModrinthTask(project, extension, spec, assembleTask)
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
                        "(dryRun=${extension.getConfig().dryRun.get()}, releaseType=${extension.getConfig().releaseType.get()}, " +
                        "loaders=${spec.loaders}, gameVersions=${spec.gameVersions})",
                )
            }

            doLast {
                ArtifactStager.stage(project, extension, spec)
            }
        }

    private fun configureCurseForgeTask(
        project: Project,
        extension: MultiloaderPublishingExtension,
        spec: PublicationSpec,
        assembleTask: TaskProvider<Task>,
    ) {
        project.tasks.named("publishCurseforge${spec.taskSuffix}") {
            dependsOn(assembleTask)
            onlyIf { extension.getPublish().getCurseforge().id.isPresent }

            doLast {
                val isDryRun = extension.getConfig().dryRun.get()
                if (!isDryRun && !extension.getPublish().getCurseforge().token.isPresent) {
                    throw IllegalStateException("[Publishing] CurseForge publishing requires publish.curseforge.token unless dryRun=true")
                }

                val curseGameVersions = MultiloaderPublishRules.curseNormalizeGameVersions(spec.gameVersions)
                project.logger.lifecycle("[Publishing] Destinations=[curseforge]")
                project.logger.lifecycle("[Publishing] loaders=${spec.loaders}")
                project.logger.lifecycle("[Publishing] gameVersions=${spec.gameVersions}")

                CurseForgeReleasePublisher.publish(
                    project = project,
                    extension = extension,
                    changelog = ReleaseChangelogSelector.resolve(project, extension, spec),
                    dryRun = isDryRun,
                    gameVersions = curseGameVersions,
                    file = PublicationPlanner.stagedArtifactFile(project, spec),
                    publication = spec,
                )

                if (isDryRun) {
                    project.logger.lifecycle("[Publishing] dryRun=true -> skipping live publish")
                }
            }
        }
    }

    private fun configureModrinthTask(
        project: Project,
        extension: MultiloaderPublishingExtension,
        spec: PublicationSpec,
        assembleTask: TaskProvider<Task>,
    ) {
        project.tasks.named("publishModrinth${spec.taskSuffix}") {
            dependsOn(assembleTask)
            onlyIf { extension.getPublish().getModrinth().id.isPresent }

            doLast {
                val isDryRun = extension.getConfig().dryRun.get()
                if (!isDryRun && !extension.getPublish().getModrinth().token.isPresent) {
                    throw IllegalStateException("[Publishing] Modrinth publishing requires publish.modrinth.token unless dryRun=true")
                }

                val modrinthGameVersions = MultiloaderPublishRules.modrinthNormalizeGameVersions(spec.gameVersions)
                project.logger.lifecycle("[Publishing] Destinations=[modrinth]")
                project.logger.lifecycle("[Publishing] loaders=${spec.loaders}")
                project.logger.lifecycle("[Publishing] gameVersions=${spec.gameVersions}")

                ModrinthReleasePublisher.publish(
                    project = project,
                    extension = extension,
                    changelog = ReleaseChangelogSelector.resolve(project, extension, spec),
                    dryRun = isDryRun,
                    gameVersions = modrinthGameVersions,
                    file = PublicationPlanner.stagedArtifactFile(project, spec),
                    publication = spec,
                )

                if (isDryRun) {
                    project.logger.lifecycle("[Publishing] dryRun=true -> skipping live publish")
                }
            }
        }
    }

    private fun configureDisabledPublicationTasks(project: Project, taskSuffix: String) {
        project.tasks.named("publishingAssemble$taskSuffix") {
            onlyIf { false }
        }
        project.tasks.named("publishCurseforge$taskSuffix") {
            onlyIf { false }
        }
        project.tasks.named("publishModrinth$taskSuffix") {
            onlyIf { false }
        }
    }
}
