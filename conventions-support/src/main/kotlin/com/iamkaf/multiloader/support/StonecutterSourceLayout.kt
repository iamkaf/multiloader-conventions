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

        sourceSets.named("main") {
            if (!usesStonecutter) {
                resources.srcDir(project.file("src/main/generated"))
                return@named
            }

            val generatedJavaDir = project.layout.buildDirectory.dir("generated/stonecutter/main/java")
            val generatedResourcesDir = project.layout.buildDirectory.dir("generated/stonecutter/main/resources")
            val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
            val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")

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
                from(project.rootProject.file("common/src/main/generated"))
                from(project.rootProject.file("src/main/generated"))
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
        val commonGeneratedJavaDir = commonProject.layout.buildDirectory.dir("generated/stonecutter/main/java")
        val commonGeneratedResourcesDir = commonProject.layout.buildDirectory.dir("generated/stonecutter/main/resources")
        val generatedJavaDir = project.layout.buildDirectory.dir("generated/stonecutter/main/java")
        val generatedResourcesDir = project.layout.buildDirectory.dir("generated/stonecutter/main/resources")
        val mergedJavaDir = project.layout.buildDirectory.dir("generated/merged/main/java")
        val mergedResourcesDir = project.layout.buildDirectory.dir("generated/merged/main/resources")
        val versionDir = project.rootProject.file("versions/$minecraftVersion")

        project.tasks.register(STAGE_JAVA_TASK, Sync::class.java) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            dependsOn(commonProject.tasks.named("stonecutterGenerate"))
            dependsOn(project.tasks.named("stonecutterGenerate"))
            from(commonGeneratedJavaDir)
            from(generatedJavaDir)
            val versionCommonJavaDir = versionDir.toPath().resolve("common/src/main/java").toFile()
            if (versionCommonJavaDir.isDirectory) {
                from(versionCommonJavaDir)
            }
            val versionLoaderJavaDir = versionDir.toPath().resolve("$loader/src/main/java").toFile()
            if (versionLoaderJavaDir.isDirectory) {
                from(versionLoaderJavaDir)
            }
            into(mergedJavaDir)
        }

        project.tasks.register(STAGE_RESOURCES_TASK, Sync::class.java) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            dependsOn(commonProject.tasks.named("stonecutterGenerate"))
            dependsOn(project.tasks.named("stonecutterGenerate"))
            from(commonGeneratedResourcesDir)
            from(generatedResourcesDir)
            from(project.rootProject.file("common/src/main/generated"))
            from(project.rootProject.file("src/main/generated"))
            val versionCommonResourcesDir = versionDir.toPath().resolve("common/src/main/resources").toFile()
            if (versionCommonResourcesDir.isDirectory) {
                from(versionCommonResourcesDir)
            }
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
}
