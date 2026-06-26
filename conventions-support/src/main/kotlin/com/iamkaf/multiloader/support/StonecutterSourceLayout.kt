package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

object StonecutterSourceLayout {
    const val STAGE_JAVA_TASK = "stageMergedJavaSources"
    const val STAGE_RESOURCES_TASK = "stageMergedResources"

    @JvmStatic
    fun configureCommon(project: Project, minecraftVersion: String, usesStonecutter: Boolean) {
        val versionDir = project.rootProject.file("versions/$minecraftVersion")
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)

        sourceSets.named("main") { main ->
            if (!usesStonecutter) {
                main.resources.srcDir(project.file("src/main/generated"))
                return@named
            }

            val generatedJavaDir = project.layout.buildDirectory.dir("generated/stonecutter/main/java")
            val generatedResourcesDir = project.layout.buildDirectory.dir("generated/stonecutter/main/resources")
            val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
            val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")

            project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) { task ->
                task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
                task.dependsOn(project.tasks.named("stonecutterGenerate"))
                task.from(generatedJavaDir)
                val versionJavaDir = versionDir.toPath().resolve("common/src/main/java").toFile()
                if (versionJavaDir.isDirectory) {
                    task.from(versionJavaDir)
                }
                task.into(mergedJavaDir)
            }

            project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) { task ->
                task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
                task.dependsOn(project.tasks.named("stonecutterGenerate"))
                task.from(generatedResourcesDir)
                task.from(project.rootProject.file("common/src/main/generated"))
                task.from(project.rootProject.file("src/main/generated"))
                val versionResourcesDir = versionDir.toPath().resolve("common/src/main/resources").toFile()
                if (versionResourcesDir.isDirectory) {
                    task.from(versionResourcesDir)
                }
                task.into(mergedResourcesDir)
            }

            main.java.setSrcDirs(listOf(mergedJavaDir.get().asFile))
            main.resources.setSrcDirs(listOf(mergedResourcesDir.get().asFile))
        }
    }

    @JvmStatic
    fun configureLoader(project: Project, loader: String, minecraftVersion: String, commonProject: Project) {
        val commonGeneratedJavaDir = commonProject.layout.buildDirectory.dir("generated/stonecutter/main/java")
        val commonGeneratedResourcesDir = commonProject.layout.buildDirectory.dir("generated/stonecutter/main/resources")
        val generatedJavaDir = project.layout.buildDirectory.dir("generated/stonecutter/main/java")
        val generatedResourcesDir = project.layout.buildDirectory.dir("generated/stonecutter/main/resources")
        val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
        val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")
        val versionDir = project.rootProject.file("versions/$minecraftVersion")

        project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.dependsOn(commonProject.tasks.named("stonecutterGenerate"))
            task.dependsOn(project.tasks.named("stonecutterGenerate"))
            task.from(commonGeneratedJavaDir)
            task.from(generatedJavaDir)
            val versionCommonJavaDir = versionDir.toPath().resolve("common/src/main/java").toFile()
            if (versionCommonJavaDir.isDirectory) {
                task.from(versionCommonJavaDir)
            }
            val versionLoaderJavaDir = versionDir.toPath().resolve("$loader/src/main/java").toFile()
            if (versionLoaderJavaDir.isDirectory) {
                task.from(versionLoaderJavaDir)
            }
            task.into(mergedJavaDir)
        }

        project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.dependsOn(commonProject.tasks.named("stonecutterGenerate"))
            task.dependsOn(project.tasks.named("stonecutterGenerate"))
            task.from(commonGeneratedResourcesDir)
            task.from(generatedResourcesDir)
            task.from(project.rootProject.file("common/src/main/generated"))
            task.from(project.rootProject.file("src/main/generated"))
            val versionCommonResourcesDir = versionDir.toPath().resolve("common/src/main/resources").toFile()
            if (versionCommonResourcesDir.isDirectory) {
                task.from(versionCommonResourcesDir)
            }
            val versionLoaderResourcesDir = versionDir.toPath().resolve("$loader/src/main/resources").toFile()
            if (versionLoaderResourcesDir.isDirectory) {
                task.from(versionLoaderResourcesDir)
            }
            task.into(mergedResourcesDir)
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.named("main") { main ->
            main.java.setSrcDirs(listOf(mergedJavaDir.get().asFile))
            main.resources.setSrcDirs(listOf(mergedResourcesDir.get().asFile))
        }

        attachStagingDependencies(project, commonProject)
    }

    @JvmStatic
    fun configureGraphOnly(project: Project) {
        val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
        val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")

        project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.into(mergedJavaDir)
        }

        project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.into(mergedResourcesDir)
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.named("main") { main ->
            main.java.setSrcDirs(listOf(mergedJavaDir.get().asFile))
            main.resources.setSrcDirs(listOf(mergedResourcesDir.get().asFile))
        }
    }

    @JvmStatic
    fun attachStagingDependencies(project: Project, commonProject: Project? = null) {
        listOf("compileJava", "sourcesJar", "javadoc").forEach { taskName ->
            project.tasks.named(taskName) { task ->
                commonProject?.let { task.dependsOn(it.tasks.named("stonecutterGenerate")) }
                task.dependsOn(project.tasks.named("stonecutterGenerate"))
                task.dependsOn(project.tasks.named(STAGE_JAVA_TASK))
            }
        }
        listOf("processResources", "sourcesJar").forEach { taskName ->
            project.tasks.named(taskName) { task ->
                task.dependsOn(project.tasks.named(STAGE_RESOURCES_TASK))
            }
        }
        project.tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach { task ->
            task.dependsOn(project.tasks.named(STAGE_JAVA_TASK))
            task.dependsOn(project.tasks.named(STAGE_RESOURCES_TASK))
        }
    }

    @JvmStatic
    fun excludeCacheFromArchives(project: Project) {
        project.tasks.withType(ProcessResources::class.java).configureEach { task ->
            task.exclude(".cache/**")
        }
        project.tasks.withType(Jar::class.java).configureEach { task ->
            task.exclude(".cache/**")
        }
    }
}
