package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

object StonecutterSourceLayout {
    const val STAGE_JAVA_TASK = "stageMergedJavaSources"
    const val STAGE_RESOURCES_TASK = "stageMergedResources"

    @JvmStatic
    fun configureCommon(project: Project, minecraftVersion: String, usesStonecutter: Boolean) {
        val versionDir = project.rootProject.file("versions/$minecraftVersion")
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)

        sourceSets.named("main") {
            if (!usesStonecutter) {
                resources.srcDir(project.file("src/main/generated"))
                return@named
            }

            val generatedJavaDir = project.layout.buildDirectory.dir("generated/stonecutter/main/java")
            val generatedResourcesDir = project.layout.buildDirectory.dir("generated/stonecutter/main/resources")
            val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
            val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")
            val versionGeneratedResourcesDir =
                DatagenOutputPlanner.commonGeneratedResourcesRoot(project, minecraftVersion, true)

            project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) {
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
                dependsOn(project.tasks.named("stonecutterGenerate"))
                from(generatedJavaDir)
                val versionJavaDir = versionDir.toPath().resolve("common/src/main/java").toFile()
                if (versionJavaDir.isDirectory) {
                    from(versionJavaDir)
                }
                into(mergedJavaDir)
            }

            project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) {
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
                dependsOn(project.tasks.named("stonecutterGenerate"))
                from(generatedResourcesDir)
                from(versionGeneratedResourcesDir)
                val versionResourcesDir = versionDir.toPath().resolve("common/src/main/resources").toFile()
                if (versionResourcesDir.isDirectory) {
                    from(versionResourcesDir)
                }
                into(mergedResourcesDir)
            }

            java.setSrcDirs(listOf(mergedJavaDir.get().asFile))
            resources.setSrcDirs(listOf(mergedResourcesDir.get().asFile))
        }
    }

    @JvmStatic
    fun configureLoader(project: Project, loader: String, minecraftVersion: String, commonProject: Project) {
        project.evaluationDependsOn(commonProject.path)

        val commonMergedJavaDir = commonProject.layout.buildDirectory.dir("generated/merged/main/java")
        val commonMergedResourcesDir = commonProject.layout.buildDirectory.dir("generated/merged/main/resources")
        val generatedJavaDir = project.layout.buildDirectory.dir("generated/stonecutter/main/java")
        val generatedResourcesDir = project.layout.buildDirectory.dir("generated/stonecutter/main/resources")
        val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
        val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")
        val versionDir = project.rootProject.file("versions/$minecraftVersion")
        val versionGeneratedResourcesDir =
            DatagenOutputPlanner.loaderGeneratedResourcesRoot(project, loader, minecraftVersion)

        project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            dependsOn(commonProject.tasks.named(STAGE_JAVA_TASK))
            dependsOn(project.tasks.named("stonecutterGenerate"))
            from(commonMergedJavaDir)
            from(generatedJavaDir)
            val versionLoaderJavaDir = versionDir.toPath().resolve("$loader/src/main/java").toFile()
            if (versionLoaderJavaDir.isDirectory) {
                from(versionLoaderJavaDir)
            }
            into(mergedJavaDir)
        }

        project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            dependsOn(commonProject.tasks.named(STAGE_RESOURCES_TASK))
            dependsOn(project.tasks.named("stonecutterGenerate"))
            from(commonMergedResourcesDir)
            from(generatedResourcesDir)
            from(versionGeneratedResourcesDir)
            val versionLoaderResourcesDir = versionDir.toPath().resolve("$loader/src/main/resources").toFile()
            if (versionLoaderResourcesDir.isDirectory) {
                from(versionLoaderResourcesDir)
            }
            into(mergedResourcesDir)
        }
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.named("main") {
            java.setSrcDirs(listOf(mergedJavaDir.get().asFile))
            resources.setSrcDirs(listOf(mergedResourcesDir.get().asFile))
        }

        attachStagingDependencies(project, commonProject)
    }

    @JvmStatic
    fun configureGraphOnly(project: Project) {
        val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
        val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")

        project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            into(mergedJavaDir)
        }

        project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            into(mergedResourcesDir)
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.named("main") {
            java.setSrcDirs(listOf(mergedJavaDir.get().asFile))
            resources.setSrcDirs(listOf(mergedResourcesDir.get().asFile))
        }
    }

    @JvmStatic
    fun addCommonResourceLane(project: Project, rootName: String, path: String) {
        val directory = project.rootProject.file("$rootName/$path")

        if (!addResourcesToStageTask(project, directory)) {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            sourceSets.named("main") {
                resources.srcDir(directory)
            }
        }
    }

    @JvmStatic
    fun attachStagingDependencies(project: Project, commonProject: Project? = null) {
        listOf("compileJava", "sourcesJar", "javadoc").forEach { taskName ->
            project.tasks.matching { it.name == taskName }.configureEach {
                commonProject?.let { dependsOn(it.tasks.named("stonecutterGenerate")) }
                dependsOn(project.tasks.named("stonecutterGenerate"))
                dependsOn(project.tasks.named(STAGE_JAVA_TASK))
            }
        }
        listOf("processResources", "sourcesJar").forEach { taskName ->
            project.tasks.matching { it.name == taskName }.configureEach {
                dependsOn(project.tasks.named(STAGE_RESOURCES_TASK))
            }
        }
        project.tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
            dependsOn(project.tasks.named(STAGE_JAVA_TASK))
            dependsOn(project.tasks.named(STAGE_RESOURCES_TASK))
        }
    }

    @JvmStatic
    fun excludeCacheFromArchives(project: Project) {
        project.tasks.withType(ProcessResources::class.java).configureEach {
            exclude(".cache/**")
        }
        project.tasks.withType(Jar::class.java).configureEach {
            exclude(".cache/**")
        }
    }

    private fun addResourcesToStageTask(project: Project, directory: File): Boolean {
        val stageResources = try {
            project.tasks.named(STAGE_RESOURCES_TASK, Sync::class.java)
        } catch (_: UnknownTaskException) {
            null
        }
        stageResources?.configure {
            from(directory)
        }
        return stageResources != null
    }
}
