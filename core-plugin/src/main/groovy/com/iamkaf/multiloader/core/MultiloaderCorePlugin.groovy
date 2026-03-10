package com.iamkaf.multiloader.core

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

class MultiloaderCorePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply(JavaLibraryPlugin)

        configureRepositories(project)
        configureCoordinates(project)
        configureJava(project)
        configureManifests(project)
    }

    private static void configureRepositories(Project project) {
        project.repositories.mavenLocal()
        project.repositories.maven { repo ->
            repo.url = project.uri('https://maven.kaf.sh')
        }
        project.repositories.mavenCentral()
    }

    private static void configureCoordinates(Project project) {
        if (project.rootProject.findProperty('group') != null) {
            project.group = project.rootProject.findProperty('group').toString()
        }

        if (project.rootProject.findProperty('version') != null) {
            project.version = project.rootProject.findProperty('version').toString()
        }
    }

    private static void configureJava(Project project) {
        def javaVersion = project.findProperty('java_version')?.toString()?.toInteger()
        if (javaVersion != null) {
            project.extensions.configure(JavaPluginExtension) { java ->
                java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
            }
        }

        project.tasks.withType(JavaCompile).configureEach { task ->
            task.options.encoding = 'UTF-8'
        }
    }

    private static void configureManifests(Project project) {
        project.tasks.withType(Jar).configureEach { task ->
            task.manifest.attributes([
                'Implementation-Title'  : project.rootProject.findProperty('mod_name') ?: project.rootProject.name,
                'Implementation-Version': project.version.toString(),
                'Built-By'              : 'multiloader-conventions',
            ])
        }
    }
}

