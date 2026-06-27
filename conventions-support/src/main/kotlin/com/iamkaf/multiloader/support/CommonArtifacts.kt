package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

object CommonArtifacts {
    fun configureConsumableCommonArtifacts(project: Project) {
        val commonJava = project.configurations.maybeCreate("commonJava")
        commonJava.isCanBeResolved = false
        commonJava.isCanBeConsumed = true

        val commonResources = project.configurations.maybeCreate("commonResources")
        commonResources.isCanBeResolved = false
        commonResources.isCanBeConsumed = true

        project.afterEvaluate {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            val main = sourceSets.getByName("main")
            main.java.sourceDirectories.files.forEach { directory ->
                project.artifacts.add(commonJava.name, directory)
            }
            main.resources.sourceDirectories.files.forEach { directory ->
                project.artifacts.add(commonResources.name, directory)
            }
        }
    }
}
