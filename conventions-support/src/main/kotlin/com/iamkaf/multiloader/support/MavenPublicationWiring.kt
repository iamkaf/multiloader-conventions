package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

object MavenPublicationWiring {
    fun configureJavaComponentPublication(project: Project, context: MultiloaderProjectContext) {
        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                from(project.components.getByName("java"))
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
            }
            context.publishingRepositories(this, project.version.toString())
        }
    }

    fun configureFabricRemappedPublication(project: Project, context: MultiloaderProjectContext) {
        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()

                val remapJar = project.tasks.named("remapJar")
                artifact(remapJar) {
                    builtBy(remapJar)
                }

                val sourcesTaskName = if (project.tasks.findByName("remapSourcesJar") != null) {
                    "remapSourcesJar"
                } else {
                    "sourcesJar"
                }
                val sourcesJar = project.tasks.named(sourcesTaskName)
                artifact(sourcesJar) {
                    builtBy(sourcesJar)
                }

                val javadocJar = project.tasks.named("javadocJar")
                artifact(javadocJar) {
                    builtBy(javadocJar)
                }
            }
            context.publishingRepositories(this, project.version.toString())
        }
    }

    fun configureFlatJavaPublication(project: Project) {
        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                groupId = project.group.toString()
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
                from(project.components.getByName("java"))
            }
            RepositoryPolicy.configurePublishingRepositories(this, project.version.toString())
        }
    }
}
