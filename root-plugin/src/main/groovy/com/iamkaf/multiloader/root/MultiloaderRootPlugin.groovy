package com.iamkaf.multiloader.root

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderRootPlugin implements Plugin<Project> {

    private static final List<String> REQUIRED_PROPERTIES = [
        'mod_name',
        'mod_id',
        'platform_minecraft_version',
        'java_version',
    ]

    @Override
    void apply(Project project) {
        if (project != project.rootProject) {
            throw new GradleException('com.iamkaf.multiloader.root must be applied to the root project only.')
        }

        applyCoordinates(project)
        registerSharedHelpers(project)
        registerValidationTask(project)
        registerAggregateTask(project, 'buildAllLoaders', 'build')
        registerAggregateTask(project, 'checkAllLoaders', 'check')
        registerRunClientTask(project, 'runClientFabric', 'fabric')
        registerRunClientTask(project, 'runClientForge', 'forge')
        registerRunClientTask(project, 'runClientNeoForge', 'neoforge')
    }

    private static void registerSharedHelpers(Project project) {
        def extractHeaderLatestFooterFromChangelog = { String completeChangelog ->
            def header = (completeChangelog =~ /(?ms)\A.*?(?=^## \d+\.\d+\.\d+)/)[0]
            def latest = (completeChangelog =~ /(?ms)^## \d+\.\d+\.\d+[\s\S]*?(?=^## (?:\d+\.\d+\.\d+|[^0-9]|$))/)[0]
            def footer = (completeChangelog =~ /(?ms)^## Types of changes[\s\S]*/)[0]

            header + latest + footer
        }
        def publisherJavaVersionsFor = { Object javaVersionValue ->
            def javaVersion = javaVersionValue.toString().toInteger()
            javaVersion >= 21
                ? [JavaVersion.VERSION_21, JavaVersion.VERSION_22]
                : [JavaVersion.toVersion(javaVersion)]
        }

        project.allprojects { candidate ->
            candidate.ext.set('extractHeaderLatestFooterFromChangelog', extractHeaderLatestFooterFromChangelog)
            candidate.ext.set('publisherJavaVersionsFor', publisherJavaVersionsFor)
            candidate.ext.set('configureCommonPublisher', { publisherExtension, String loaderName, Iterable<JavaVersion> javaVersions ->
                publisherExtension.apiKeys {
                    modrinth System.getenv('MODRINTH_TOKEN')
                    curseforge System.getenv('CURSEFORGE_TOKEN')
                }

                publisherExtension.setDebug(Boolean.valueOf(candidate.findProperty('dry_run')))
                publisherExtension.setCurseID(candidate.findProperty('curse_id'))
                publisherExtension.setModrinthID(candidate.findProperty('modrinth_id'))
                publisherExtension.setVersionType(candidate.findProperty('release_type'))
                publisherExtension.setChangelog(candidate.extractHeaderLatestFooterFromChangelog(candidate.rootProject.file('../changelog.md').text))
                publisherExtension.setProjectVersion(candidate.rootProject.version)
                publisherExtension.setDisplayName("${candidate.findProperty('mod_id')}-${candidate.name}-${candidate.version}")
                publisherExtension.setGameVersions(candidate.findProperty('game_versions').toString().split(','))
                publisherExtension.setLoaders(loaderName)
                publisherExtension.setCurseEnvironment(candidate.findProperty('mod_environment'))
                publisherExtension.setIsManualRelease(false)
                publisherExtension.setArtifact("build/libs/${candidate.findProperty('mod_id')}-${candidate.name}-${candidate.version}.jar")
                publisherExtension.setDisableEmptyJarCheck(false)
                publisherExtension.setJavaVersions(javaVersions)

                def modrinthDepends = candidate.findProperty('mod_modrinth_depends')?.toString()?.trim()
                if (modrinthDepends) {
                    publisherExtension.modrinthDepends {
                        required(modrinthDepends.split(','))
                    }
                }

                def curseDepends = candidate.findProperty('mod_curse_depends')?.toString()?.trim()
                if (curseDepends) {
                    publisherExtension.curseDepends {
                        required(curseDepends.split(','))
                    }
                }
            })
        }
    }

    private static void applyCoordinates(Project project) {
        def groupValue = project.findProperty('group')
        def versionValue = project.findProperty('version')

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
