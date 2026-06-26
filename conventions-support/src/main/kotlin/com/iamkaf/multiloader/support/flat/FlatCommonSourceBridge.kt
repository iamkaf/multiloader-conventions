package com.iamkaf.multiloader.support.flat

import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

object FlatCommonSourceBridge {
    private val loaderProjects = listOf("fabric", "forge", "neoforge")

    fun configureLoaderBridge(project: Project) {
        if (project.name !in loaderProjects) return
        project.rootProject.findProject(":common") ?: return

        val commonJava = project.configurations.maybeCreate("commonJava")
        commonJava.isCanBeResolved = true
        commonJava.isCanBeConsumed = false

        val commonResources = project.configurations.maybeCreate("commonResources")
        commonResources.isCanBeResolved = true
        commonResources.isCanBeConsumed = false

        val commonDependency = project.dependencies.project(mapOf("path" to ":common")) as ProjectDependency
        commonDependency.capabilities {
            requireCapability("${project.group}:${FlatProjectAccess.requiredProperty(project, "mod.id")}")
        }
        project.dependencies.add("compileOnly", commonDependency)
        project.dependencies.add(
            "commonJava",
            project.dependencies.project(mapOf("path" to ":common", "configuration" to "commonJava")),
        )
        project.dependencies.add(
            "commonResources",
            project.dependencies.project(mapOf("path" to ":common", "configuration" to "commonResources")),
        )

        project.tasks.named("compileJava", JavaCompile::class.java) {
            dependsOn(commonJava)
            source(commonJava)
        }

        project.tasks.named("processResources", ProcessResources::class.java) {
            dependsOn(commonResources)
            from(commonResources)
            exclude(".cache/**")
        }

        project.tasks.named("javadoc") {
            dependsOn(commonJava)
            GroovyGradleDsl.invoke(this, "source", commonJava)
        }

        project.tasks.named("sourcesJar", Jar::class.java) {
            dependsOn(commonJava)
            from(commonJava)
            dependsOn(commonResources)
            from(commonResources)
            exclude(".cache/**")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
