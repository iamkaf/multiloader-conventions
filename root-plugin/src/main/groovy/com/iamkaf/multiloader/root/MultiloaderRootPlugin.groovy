package com.iamkaf.multiloader.root

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderRootPlugin implements Plugin<Project> {

    private static final List<String> REQUIRED_PROPERTIES = [
        'mod.name',
        'mod.id',
        'project.minecraft',
        'project.java',
    ]

    @Override
    void apply(Project project) {
        if (project != project.rootProject) {
            throw new GradleException('com.iamkaf.multiloader.root must be applied to the root project only.')
        }

        applyCoordinates(project)
        registerValidationTask(project)
        registerAggregateTask(project, 'buildAllLoaders', 'build')
        registerAggregateTask(project, 'checkAllLoaders', 'check')
        registerRunClientTask(project, 'runClientFabric', 'fabric')
        registerRunClientTask(project, 'runClientForge', 'forge')
        registerRunClientTask(project, 'runClientNeoForge', 'neoforge')
    }

    private static void applyCoordinates(Project project) {
        def groupValue = project.findProperty('project.group')
        def versionValue = project.findProperty('project.version')

        if (groupValue != null) {
            project.group = groupValue.toString()
        }

        if (versionValue != null) {
            project.version = versionValue.toString()
        }
    }

    private static void registerValidationTask(Project project) {
        project.tasks.register('validateConventionProperties') { task ->
            task.group = 'verification'
            task.description = 'Checks that the required convention properties are present.'
            task.doLast {
                def missing = REQUIRED_PROPERTIES.findAll { propertyName ->
                    def value = project.findProperty(propertyName)
                    value == null || value.toString().isBlank()
                }

                if (!missing.isEmpty()) {
                    throw new GradleException("Missing required convention properties: ${missing.join(', ')}")
                }
            }
        }
    }

    private static void registerAggregateTask(Project project, String taskName, String childTaskName) {
        project.tasks.register(taskName) { task ->
            task.group = 'build'
            task.description = "Runs ${childTaskName} on every included loader project."

            project.gradle.projectsEvaluated {
                task.dependsOn(project.subprojects
                    .findAll { child -> child.tasks.findByName(childTaskName) != null }
                    .collect { child -> "${child.path}:${childTaskName}" })
            }
        }
    }

    private static void registerRunClientTask(Project project, String taskName, String projectName) {
        project.tasks.register(taskName) { task ->
            task.group = 'run'
            task.description = "Runs ${projectName}:runClient when that project exists."

            project.gradle.projectsEvaluated {
                def child = project.findProject(":${projectName}")
                if (child != null && child.tasks.findByName('runClient') != null) {
                    task.dependsOn("${child.path}:runClient")
                }
            }
        }
    }
}
