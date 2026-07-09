package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.BuildToolsVersions
import com.iamkaf.multiloader.support.VersionPolicy
import groovy.json.JsonOutput
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import java.io.File
import java.util.Properties

object BuildGraphReporter {
    private val knownLoaders = listOf("fabric", "forge", "neoforge")

    fun registerTasks(project: Project) {
        val graphFile = project.layout.buildDirectory.file("reports/multiloader/graph.json")

        project.tasks.register("writeMultiloaderGraph") {
            group = "help"
            description = "Writes the resolved multiloader build graph as JSON."
            outputs.file(graphFile)
            doLast {
                val outputFile = graphFile.get().asFile
                outputFile.parentFile.mkdirs()
                outputFile.writeText(graphJson(project) + System.lineSeparator())
                project.logger.lifecycle("Wrote ${project.relativePath(outputFile)}")
            }
        }

        project.tasks.register("printMultiloaderGraph") {
            group = "help"
            description = "Prints the resolved multiloader build graph as JSON."
            doLast {
                println(graphJson(project))
            }
        }
    }

    fun graphJson(project: Project): String =
        JsonOutput.prettyPrint(JsonOutput.toJson(buildGraph(project)))

    fun buildGraph(project: Project): Map<String, Any?> {
        val versionDirs = RootVersionMatrix.versionDirectories(project)
        val teaKitNodes = TeaKitNodeReader.read(project)

        return linkedMapOf(
            "schemaVersion" to 1,
            "project" to linkedMapOf(
                "name" to project.name,
                "group" to project.group?.toString(),
                "version" to project.version?.toString(),
            ),
            "mod" to linkedMapOf(
                "id" to optionalProjectProperty(project, "mod.id"),
                "name" to optionalProjectProperty(project, "mod.name"),
            ),
            "conventions" to linkedMapOf(
                "version" to optionalProjectProperty(project, "project.plugins"),
                "stonecutterVersion" to BuildToolsVersions.required("stonecutterPlugin"),
            ),
            "versions" to if (versionDirs.isEmpty()) {
                listOf(flatVersionGraph(project, teaKitNodes))
            } else {
                versionDirs.map { versionGraph(project, it, teaKitNodes) }
            },
        )
    }

    private fun flatVersionGraph(project: Project, teaKitNodes: List<TeaKitNode>): Map<String, Any?> {
        val props = Properties()
        listOf(
            "project.minecraft", "project.version", "project.java", "project.build-java", "project.enabled-loaders",
            "mod.minecraft-range", "mod.fabric-range", "mod.forge-loader-range", "mod.neoforge-loader-range",
        ).forEach { name ->
            optionalProjectProperty(project, name)?.let { props.setProperty(name, it) }
        }

        val minecraftVersion = props.getProperty("project.minecraft")
        return versionGraph(project, null, teaKitNodes, props, minecraftVersion)
    }

    private fun versionGraph(
        project: Project,
        versionDir: File?,
        teaKitNodes: List<TeaKitNode>,
        providedProps: Properties? = null,
        providedVersion: String? = null,
    ): Map<String, Any?> {
        val props = providedProps ?: RootVersionMatrix.versionMetadata(versionDir!!)
        val minecraftVersion = providedVersion ?: props.getProperty("project.minecraft") ?: versionDir!!.name
        val enabledLoaders = RootVersionMatrix.parseEnabledLoaders(props)
        val ranges = linkedMapOf(
            "minecraft" to props.getProperty("mod.minecraft-range"),
            "fabric" to props.getProperty("mod.fabric-range"),
            "forge" to props.getProperty("mod.forge-loader-range"),
            "neoforge" to props.getProperty("mod.neoforge-loader-range"),
        ).filterValues { it != null }

        return linkedMapOf(
            "name" to minecraftVersion,
            "minecraft" to minecraftVersion,
            "projectVersion" to (props.getProperty("project.version") ?: project.version?.toString()),
            "java" to props.getProperty("project.java"),
            "buildJava" to props.getProperty("project.build-java"),
            "catalog" to catalogName(minecraftVersion),
            "enabledLoaders" to enabledLoaders,
            "ranges" to ranges,
            "horizontal" to horizontalGraph(project, minecraftVersion, enabledLoaders, props),
            "common" to commonGraph(project, minecraftVersion),
            "loaders" to knownLoaders.map { loader ->
                loaderGraph(project, minecraftVersion, props, loader, loader in enabledLoaders, teaKitNodes)
            },
        )
    }

    private fun horizontalGraph(
        project: Project,
        minecraftVersion: String?,
        enabledLoaders: List<String>,
        props: Properties,
    ): Map<String, Any?> {
        val horizontal = project.extensions.findByType(MultiloaderArtifactsExtension::class.java)?.getHorizontalMerge()
        val globallyEnabled = horizontal?.enabled?.orNull == true
        val configuredVersions = horizontal?.versions?.orNull.orEmpty()
        val enabledForVersion = globallyEnabled && minecraftVersion != null &&
            (configuredVersions.isEmpty() || minecraftVersion in configuredVersions)
        val suffix = minecraftVersion?.let(RootTaskNames::taskSuffix)
        val mergeTask = suffix?.let { taskPath(project, "mergeHorizontalJar$it") }
        val validateTask = suffix?.let { taskPath(project, "validateHorizontalJar$it") }
        val planned = enabledForVersion && mergeTask != null && validateTask != null
        val tier = if (planned) minecraftVersion?.let(HorizontalMergePolicy::tier) else null
        val unsafeAcknowledged = minecraftVersion != null &&
            minecraftVersion in horizontal?.allowUnstableVersions?.orNull.orEmpty()
        val projectVersion = props.getProperty("project.version") ?: project.version.toString()
        val modId = optionalProjectProperty(project, "mod.id") ?: project.name
        val artifactPath = if (planned) {
            project.relativePath(
                project.layout.buildDirectory.file(
                    HorizontalArtifactNaming.relativePath(minecraftVersion, modId, projectVersion),
                ).get().asFile,
            )
        } else {
            null
        }

        return linkedMapOf(
            "enabled" to enabledForVersion,
            "planned" to planned,
            "stabilityTier" to when (tier) {
                HorizontalMergeTier.STABLE -> "stable"
                HorizontalMergeTier.UNSTABLE_RELOCATED -> "unsafe-relocated"
                null -> null
            },
            "unsafeAcknowledged" to unsafeAcknowledged,
            "selectedLoaders" to if (planned) enabledLoaders.filter { it in knownLoaders } else emptyList<String>(),
            "mergeTask" to mergeTask.takeIf { planned },
            "validateTask" to validateTask.takeIf { planned },
            "artifactPath" to artifactPath,
            "publishable" to false,
            "nonPublishableReason" to if (planned) {
                "Cross-loader platform dependency semantics are not represented safely by the publishing plugin."
            } else {
                null
            },
            "platformPublishTasks" to emptyMap<String, String>(),
        )
    }

    private fun commonGraph(project: Project, minecraftVersion: String?): Map<String, Any?> {
        val commonPath = if (minecraftVersion == null) ":common" else ":common:$minecraftVersion"
        val commonProject = project.findProject(commonPath)
        return linkedMapOf(
            "projectPath" to commonPath,
            "projectExists" to (commonProject != null),
            "compileTask" to taskPath(commonProject, "compileJava"),
            "buildTask" to taskPath(commonProject, "build"),
            "mavenPublishTasks" to publishTasks(commonProject, "publish", "PublicationTo"),
        )
    }

    private fun loaderGraph(
        project: Project,
        minecraftVersion: String?,
        props: Properties,
        loader: String,
        enabled: Boolean,
        teaKitNodes: List<TeaKitNode>,
    ): Map<String, Any?> {
        val loaderPath = if (minecraftVersion == null) ":$loader" else ":$loader:$minecraftVersion"
        val loaderProject = project.findProject(loaderPath)
        val artifactTask = artifactTaskName(loader, minecraftVersion)
        val artifactTaskPath = taskPath(loaderProject, artifactTask) ?: taskPath(loaderProject, "jar")
        val artifactPath = if (artifactTaskPath == null) null else artifactPath(project, loaderProject, loader, minecraftVersion, props)
        val publishSuffix = RootTaskNames.taskSuffix(if (minecraftVersion == null) loader else "$minecraftVersion-$loader")
        val platformPublishTasks = linkedMapOf(
            "modrinth" to taskPath(project, "publishModrinth$publishSuffix"),
            "curseforge" to taskPath(project, "publishCurseforge$publishSuffix"),
        ).filterValues { it != null }

        return linkedMapOf(
            "name" to loader,
            "enabled" to enabled,
            "projectPath" to loaderPath,
            "projectExists" to (loaderProject != null),
            "loaderRootExists" to project.file(loader).isDirectory,
            "buildTask" to taskPath(loaderProject, "build"),
            "runClientTask" to taskPath(loaderProject, "runClient"),
            "artifactTask" to artifactTaskPath,
            "artifactPath" to artifactPath,
            "mavenPublishTasks" to publishTasks(loaderProject, "publish", "PublicationTo"),
            "platformPublishTasks" to platformPublishTasks,
            "scenarioNodes" to teaKitNodes
                .filter { node -> node.loader == loader && (minecraftVersion == null || node.minecraft == minecraftVersion) }
                .map { it.name }
                .sorted(),
        )
    }

    private fun taskPath(project: Project?, taskName: String?): String? {
        if (project == null || taskName == null) return null
        if (!hasTask(project, taskName)) return null
        return taskPath(project.path, taskName)
    }

    private fun publishTasks(project: Project?, prefix: String, contains: String): List<String> {
        if (project == null) return emptyList()
        val taskPaths = linkedSetOf<String>()
        publicationNameCandidates(project).forEach { publicationName ->
            val publicationSuffix = RootTaskNames.taskSuffix(publicationName)
            addPublishTask(project, taskPaths, "$prefix$publicationSuffix${contains}MavenLocal")
            addPublishTask(project, taskPaths, "$prefix$publicationSuffix${contains}KafMavenRepository")
        }
        return taskPaths.sorted()
    }

    private fun publicationNameCandidates(project: Project): List<String> {
        val candidates = linkedSetOf("mavenJava")
        project.path.split(":")
            .filter { it == "common" || it in knownLoaders }
            .forEach { candidates.add(it) }
        if (project.name.isNotBlank()) {
            candidates.add(project.name)
        }
        return candidates.toList()
    }

    private fun addPublishTask(project: Project, taskPaths: MutableSet<String>, taskName: String) {
        taskPath(project, taskName)?.let { taskPaths.add(it) }
    }

    private fun hasTask(project: Project, taskName: String): Boolean =
        try {
            project.tasks.named(taskName)
            true
        } catch (_: UnknownTaskException) {
            false
        }

    private fun taskPath(projectPath: String, taskName: String): String =
        if (projectPath == ":") ":$taskName" else "$projectPath:$taskName"

    private fun artifactPath(
        rootProject: Project,
        targetProject: Project?,
        loader: String,
        minecraftVersion: String?,
        props: Properties,
    ): String? {
        if (targetProject == null) return null
        val projectVersion = props.getProperty("project.version") ?: rootProject.version?.toString()
        val modId = optionalProjectProperty(rootProject, "mod.id") ?: rootProject.name
        val archiveName = "$modId-$loader-$projectVersion.jar"
        return rootProject.relativePath(File(targetProject.layout.buildDirectory.get().asFile, "libs/$archiveName"))
    }

    private fun artifactTaskName(loader: String, minecraftVersion: String?): String =
        if (loader == "fabric" && minecraftVersion != null) {
            VersionPolicy.fabricPublicationArtifact(minecraftVersion).artifactTask
        } else {
            "jar"
        }

    private fun optionalProjectProperty(project: Project, name: String): String? {
        val value = project.findProperty(name) ?: return null
        val text = value.toString().trim()
        return text.ifEmpty { null }
    }

    private fun catalogName(minecraftVersion: String?): String? =
        minecraftVersion?.takeIf { it.isNotBlank() }?.let { VersionPolicy.catalogName(it) }
}
