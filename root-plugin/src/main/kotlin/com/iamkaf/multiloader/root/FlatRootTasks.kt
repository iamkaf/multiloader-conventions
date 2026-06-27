package com.iamkaf.multiloader.root

import org.gradle.api.GradleException
import org.gradle.api.Project

object FlatRootTasks {
    private val requiredProperties = listOf(
        "mod.name",
        "mod.id",
        "project.minecraft",
        "project.java",
    )

    fun register(project: Project) {
        registerValidationTask(project)
        registerAggregateTask(project, "buildAllLoaders", "build")
        registerAggregateTask(project, "checkAllLoaders", "check")
        registerRunClientTask(project, "runClientFabric", "fabric")
        registerRunClientTask(project, "runClientForge", "forge")
        registerRunClientTask(project, "runClientNeoForge", "neoforge")
    }

    private fun registerValidationTask(project: Project) {
        project.tasks.register("validateConventionProperties") {
            group = "verification"
            description = "Checks that the required convention properties are present."
            doLast {
                val missing = requiredProperties.filter { propertyName ->
                    val value = project.findProperty(propertyName)
                    value == null || value.toString().isBlank()
                }

                if (missing.isNotEmpty()) {
                    throw GradleException("Missing required convention properties: ${missing.joinToString(", ")}")
                }
            }
        }
    }

    private fun registerAggregateTask(project: Project, taskName: String, childTaskName: String) {
        project.tasks.register(taskName) {
            group = "build"
            description = "Runs $childTaskName on every included loader project."
            val aggregateTask = this
            project.gradle.projectsEvaluated {
                aggregateTask.dependsOn(project.subprojects
                    .filter { child -> child.tasks.findByName(childTaskName) != null }
                    .map { child -> "${child.path}:$childTaskName" })
            }
        }
    }

    private fun registerRunClientTask(project: Project, taskName: String, projectName: String) {
        project.tasks.register(taskName) {
            group = "run"
            description = "Runs $projectName:runClient when that project exists."
            val runClientTask = this
            project.gradle.projectsEvaluated {
                val child = project.findProject(":$projectName")
                if (child != null && child.tasks.findByName("runClient") != null) {
                    runClientTask.dependsOn("${child.path}:runClient")
                }
            }
        }
    }
}
