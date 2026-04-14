package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.publishing.MultiloaderPublishingExtension
import com.iamkaf.multiloader.translations.MultiloaderTranslationsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

import java.util.Properties

class MultiloaderRootPlugin implements Plugin<Project> {

    private static final List<String> FLAT_REQUIRED_PROPERTIES = [
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

        if (hasVersionMatrix(project)) {
            applyStonecutterRootPlugin(project)
            return
        }

        applyFlatRootPlugin(project)
    }

    private static void applyFlatRootPlugin(Project project) {
        applyCoordinates(project)
        registerFlatValidationTask(project)
        registerAggregateTask(project, 'buildAllLoaders', 'build')
        registerAggregateTask(project, 'checkAllLoaders', 'check')
        registerRunClientTask(project, 'runClientFabric', 'fabric')
        registerRunClientTask(project, 'runClientForge', 'forge')
        registerRunClientTask(project, 'runClientNeoForge', 'neoforge')
    }

    private static void applyStonecutterRootPlugin(Project project) {
        project.pluginManager.apply('com.iamkaf.multiloader.publishing')
        project.pluginManager.apply('com.iamkaf.multiloader.translations')

        applyCoordinates(project)

        def modId = requiredProperty(project, 'mod.id')
        def versionDirs = versionDirectories(project)

        project.extensions.configure(MultiloaderTranslationsExtension) { extension ->
            extension.projectSlug.set(project.providers.gradleProperty('mod.id'))
            extension.outputDir.set(project.layout.projectDirectory.dir("common/src/main/resources/assets/${modId}/lang"))
        }

        project.extensions.configure(MultiloaderPublishingExtension) { extension ->
            versionDirs.each { dir ->
                def props = loadProperties(new File(dir, 'gradle.properties'))
                def minecraftVersion = props.getProperty('project.minecraft')
                def javaVersion = props.getProperty('project.java')
                def enabledLoaders = parseEnabledLoaders(props)

                enabledLoaders.each { loaderId ->
                    extension.publication("${minecraftVersion}-${loaderId}") {
                        projectPath.set(":${loaderId}:${minecraftVersion}")
                        artifactTask.set(loaderId == 'fabric' && !minecraftVersion.startsWith('26.') ? 'remapJar' : 'jar')
                        if (loaderId == 'fabric' && !minecraftVersion.startsWith('26.')) {
                            fallbackArtifactTask.set('jar')
                        }
                        if (loaderId == 'forge') {
                            buildTasks.add('reobfJar')
                        }
                        loaders.add(loaderId)
                        gameVersions.add(minecraftVersion)
                        javaVersions.add(javaVersion)
                    }
                }
            }
        }
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

    private static void registerFlatValidationTask(Project project) {
        project.tasks.register('validateConventionProperties') { task ->
            task.group = 'verification'
            task.description = 'Checks that the required convention properties are present.'
            task.doLast {
                def missing = FLAT_REQUIRED_PROPERTIES.findAll { propertyName ->
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

    private static String requiredProperty(Project project, String name) {
        def value = project.findProperty(name)?.toString()
        if (value == null || value.isBlank()) {
            throw new GradleException("Missing required property '${name}'")
        }
        value
    }

    private static boolean hasVersionMatrix(Project project) {
        !versionDirectories(project).isEmpty()
    }

    private static List<File> versionDirectories(Project project) {
        def versionsDir = project.file('versions')
        if (!versionsDir.isDirectory()) {
            return []
        }

        versionsDir.listFiles()
            ?.findAll { candidate -> new File(candidate, 'gradle.properties').isFile() }
            ?.sort { left, right -> left.name <=> right.name }
            ?: []
    }

    private static Properties loadProperties(File file) {
        def properties = new Properties()
        file.withInputStream(properties.&load)
        properties
    }

    private static List<String> parseEnabledLoaders(Properties props) {
        props.getProperty('project.enabled-loaders', '')
            .split(',')
            .collect { it.trim() }
            .findAll { !it.isBlank() }
    }
}
