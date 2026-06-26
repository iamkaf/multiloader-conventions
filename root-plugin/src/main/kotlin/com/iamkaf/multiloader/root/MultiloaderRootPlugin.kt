package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.publishing.MultiloaderPublishingExtension
import com.iamkaf.multiloader.support.BuildToolsVersions
import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderTargetScope
import com.iamkaf.multiloader.support.VersionPolicy
import com.iamkaf.multiloader.translations.MultiloaderTranslationsExtension
import groovy.json.JsonOutput
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import java.io.File
import java.util.Locale
import java.util.Properties

class MultiloaderRootPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("com.iamkaf.multiloader.root must be applied to the root project only.")
        }

        project.extensions.create("multiloaderStonecutter", MultiloaderStonecutterExtension::class.java, project)
        configureTeaKitRunPropertyForwarding(project)

        if (hasVersionMatrix(project)) {
            applyStonecutterRootPlugin(project)
            return
        }

        applyFlatRootPlugin(project)
    }

    private fun applyFlatRootPlugin(project: Project) {
        applyCoordinates(project)
        registerFlatValidationTask(project)
        registerBuildGraphTasks(project)
        registerAggregateTask(project, "buildAllLoaders", "build")
        registerAggregateTask(project, "checkAllLoaders", "check")
        registerRunClientTask(project, "runClientFabric", "fabric")
        registerRunClientTask(project, "runClientForge", "forge")
        registerRunClientTask(project, "runClientNeoForge", "neoforge")
    }

    private fun applyStonecutterRootPlugin(project: Project) {
        project.pluginManager.apply("com.iamkaf.multiloader.publishing")
        project.pluginManager.apply("com.iamkaf.multiloader.translations")
        configureStonecutterDefaults(project)
        applyCoordinates(project)

        val modId = requiredProperty(project, "mod.id")
        val versionDirs = versionDirectories(project)
        val targetScope = MultiloaderTargetScope.fromProject(project, enabledLoadersByVersion(versionDirs))

        project.extensions.configure(MultiloaderTranslationsExtension::class.java) { extension ->
            extension.projectSlug.set(project.providers.gradleProperty("mod.id"))
            extension.outputDir.set(project.layout.projectDirectory.dir("common/src/main/resources/assets/$modId/lang"))
        }

        project.extensions.configure(MultiloaderPublishingExtension::class.java) { extension ->
            versionDirs.filter { targetScope.includesVersion(it.name) }.forEach { dir ->
                val props = versionMetadata(dir)
                val minecraftVersion = props.getProperty("project.minecraft")
                val javaVersion = props.getProperty("project.java")
                val enabledLoaders = targetScope.loadersFor(dir.name)

                enabledLoaders.forEach { loaderId ->
                    val publication = extension.getPublications().maybeCreate("$minecraftVersion-$loaderId")
                    publication.getProjectPath().set(":$loaderId:$minecraftVersion")
                    val artifact = when (loaderId) {
                        "fabric" -> VersionPolicy.fabricPublicationArtifact(minecraftVersion)
                        "forge" -> VersionPolicy.forgePublicationArtifact()
                        else -> VersionPolicy.neoForgePublicationArtifact()
                    }
                    publication.getArtifactTask().set(artifact.artifactTask)
                    artifact.fallbackArtifactTask?.let { publication.getFallbackArtifactTask().set(it) }
                    publication.getBuildTasks().addAll(artifact.buildTasks)
                    publication.getLoaders().add(loaderId)
                    publication.getGameVersions().add(minecraftVersion)
                    publication.getJavaVersions().add(javaVersion)
                }
            }
        }

        registerBuildGraphTasks(project)
    }

    private fun applyCoordinates(project: Project) {
        project.findProperty("project.group")?.let { project.group = it.toString() }
        project.findProperty("project.version")?.let { project.version = it.toString() }
    }

    private fun registerFlatValidationTask(project: Project) {
        project.tasks.register("validateConventionProperties") { task ->
            task.group = "verification"
            task.description = "Checks that the required convention properties are present."
            task.doLast {
                val missing = flatRequiredProperties.filter { propertyName ->
                    val value = project.findProperty(propertyName)
                    value == null || value.toString().isBlank()
                }

                if (missing.isNotEmpty()) {
                    throw GradleException("Missing required convention properties: ${missing.joinToString(", ")}")
                }
            }
        }
    }

    private fun registerAggregateTask(project: Project, taskName: String, childTaskName: String) {
        project.tasks.register(taskName) { task ->
            task.group = "build"
            task.description = "Runs $childTaskName on every included loader project."
            project.gradle.projectsEvaluated {
                task.dependsOn(project.subprojects
                    .filter { child -> child.tasks.findByName(childTaskName) != null }
                    .map { child -> "${child.path}:$childTaskName" })
            }
        }
    }

    private fun registerRunClientTask(project: Project, taskName: String, projectName: String) {
        project.tasks.register(taskName) { task ->
            task.group = "run"
            task.description = "Runs $projectName:runClient when that project exists."
            project.gradle.projectsEvaluated {
                val child = project.findProject(":$projectName")
                if (child != null && child.tasks.findByName("runClient") != null) {
                    task.dependsOn("${child.path}:runClient")
                }
            }
        }
    }

    private fun configureTeaKitRunPropertyForwarding(project: Project) {
        project.subprojects { child ->
            child.tasks.configureEach { task ->
                if (task.name !in teaKitRunTaskNames) return@configureEach
                val systemPropertyMethod = task.javaClass.methods.firstOrNull { method ->
                    method.name == "systemProperty" && method.parameterCount == 2
                } ?: return@configureEach

                collectTeaKitSystemProperties(project).forEach { (propertyName, value) ->
                    systemPropertyMethod.invoke(task, propertyName, value)
                }
            }
        }
    }

    private fun requiredProperty(project: Project, name: String): String {
        val value = project.findProperty(name)?.toString()
        if (value.isNullOrBlank()) {
            throw GradleException("Missing required property '$name'")
        }
        return value
    }

    private fun hasVersionMatrix(project: Project): Boolean = versionDirectories(project).isNotEmpty()

    private fun versionDirectories(project: Project): List<File> {
        val versionsDir = project.file("versions")
        if (!versionsDir.isDirectory) return emptyList()
        return versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun loadProperties(file: File): Properties =
        Properties().also { props -> file.inputStream().use(props::load) }

    private fun versionMetadata(versionDir: File): Properties {
        val metadataFile = File(versionDir, "gradle.properties")
        if (metadataFile.isFile) {
            return loadProperties(metadataFile)
        }
        return VersionPolicy.metadataProperties(versionDir.name)
    }

    private fun parseEnabledLoaders(props: Properties): List<String> =
        props.getProperty("project.enabled-loaders", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun enabledLoadersByVersion(versionDirs: List<File>): Map<String, List<String>> =
        versionDirs.associate { dir -> dir.name to parseEnabledLoaders(versionMetadata(dir)) }

    private fun registerBuildGraphTasks(project: Project) {
        val graphFile = project.layout.buildDirectory.file("reports/multiloader/graph.json")

        project.tasks.register("writeMultiloaderGraph") { task ->
            task.group = "help"
            task.description = "Writes the resolved multiloader build graph as JSON."
            task.outputs.file(graphFile)
            task.doLast {
                val outputFile = graphFile.get().asFile
                outputFile.parentFile.mkdirs()
                outputFile.writeText(graphJson(project) + System.lineSeparator())
                project.logger.lifecycle("Wrote ${project.relativePath(outputFile)}")
            }
        }

        project.tasks.register("printMultiloaderGraph") { task ->
            task.group = "help"
            task.description = "Prints the resolved multiloader build graph as JSON."
            task.doLast {
                println(graphJson(project))
            }
        }
    }

    private fun graphJson(project: Project): String =
        JsonOutput.prettyPrint(JsonOutput.toJson(buildGraph(project)))

    private fun buildGraph(project: Project): Map<String, Any?> {
        val versionDirs = versionDirectories(project)
        val teaKitNodes = readTeaKitNodes(project)

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

    private fun flatVersionGraph(project: Project, teaKitNodes: List<Map<String, String>>): Map<String, Any?> {
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
        teaKitNodes: List<Map<String, String>>,
        providedProps: Properties? = null,
        providedVersion: String? = null,
    ): Map<String, Any?> {
        val props = providedProps ?: versionMetadata(versionDir!!)
        val minecraftVersion = providedVersion ?: props.getProperty("project.minecraft") ?: versionDir!!.name
        val enabledLoaders = parseEnabledLoaders(props)
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
            "common" to commonGraph(project, minecraftVersion),
            "loaders" to knownLoaders.map { loader ->
                loaderGraph(project, minecraftVersion, props, loader, loader in enabledLoaders, teaKitNodes)
            },
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
        teaKitNodes: List<Map<String, String>>,
    ): Map<String, Any?> {
        val loaderPath = if (minecraftVersion == null) ":$loader" else ":$loader:$minecraftVersion"
        val loaderProject = project.findProject(loaderPath)
        val artifactTask = artifactTaskName(loader, minecraftVersion)
        val artifactTaskPath = taskPath(loaderProject, artifactTask) ?: taskPath(loaderProject, "jar")
        val artifactPath = if (artifactTaskPath == null) null else artifactPath(project, loaderProject, loader, minecraftVersion, props)
        val publishSuffix = taskSuffix(if (minecraftVersion == null) loader else "$minecraftVersion-$loader")
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
                .filter { node -> node["loader"] == loader && (minecraftVersion == null || node["minecraft"] == minecraftVersion) }
                .mapNotNull { it["name"] }
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
            val publicationSuffix = taskSuffix(publicationName)
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
        if (loader == "fabric" && minecraftVersion != null && !minecraftVersion.startsWith("26.")) "remapJar" else "jar"

    private fun optionalProjectProperty(project: Project, name: String): String? {
        val value = project.findProperty(name) ?: return null
        val text = value.toString().trim()
        return text.ifEmpty { null }
    }

    private fun catalogName(minecraftVersion: String?): String? =
        minecraftVersion?.takeIf { it.isNotBlank() }?.let { VersionPolicy.catalogName(it) }

    private fun readTeaKitNodes(project: Project): List<Map<String, String>> {
        val file = project.file("teakit.toml")
        if (!file.isFile) return emptyList()

        val nodes = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        file.forEachLine(Charsets.UTF_8) { line ->
            val section = Regex("""^\s*\[nodes\."([^"]+)"]\s*$""").find(line)
            if (section != null) {
                current = linkedMapOf("name" to section.groupValues[1])
                nodes.add(current!!)
                return@forEachLine
            }

            if (Regex("""^\s*\[.*]\s*$""").matches(line)) {
                current = null
                return@forEachLine
            }

            val active = current ?: return@forEachLine
            val property = Regex("""^\s*(loader|minecraft)\s*=\s*"([^"]+)"\s*$""").find(line)
            if (property != null) {
                active[property.groupValues[1]] = property.groupValues[2]
            }
        }
        return nodes.filter { it["loader"] != null || it["minecraft"] != null }
    }

    private fun taskSuffix(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { token ->
                token.substring(0, 1).uppercase(Locale.ROOT) + token.substring(1)
            }

    private fun collectTeaKitSystemProperties(project: Project): Map<String, String> {
        val properties = linkedMapOf<String, String>()
        project.gradle.startParameter.systemPropertiesArgs.forEach { (key, value) ->
            if (key.startsWith(teaKitPropertyPrefix) && value != null) {
                properties[key] = value.toString()
            }
        }

        System.getProperties().stringPropertyNames()
            .filter { it.startsWith(teaKitPropertyPrefix) }
            .sorted()
            .forEach { key ->
                System.getProperty(key)?.let { value -> properties[key] = value }
            }

        return properties
    }

    private fun configureStonecutterDefaults(project: Project) {
        project.plugins.withId("dev.kikugie.stonecutter") {
            project.extensions.configure("stonecutter", Action<Any> { stonecutter ->
                GroovyGradleDsl.invoke(
                    stonecutter,
                    "handlers",
                    GroovyGradleDsl.closure { handlers ->
                        GroovyGradleDsl.invoke(handlers, "inherit", "json5", "json")
                    },
                )
            })
        }
    }

    companion object {
        private val flatRequiredProperties = listOf(
            "mod.name",
            "mod.id",
            "project.minecraft",
            "project.java",
        )
        private val teaKitRunTaskNames = setOf("runClient", "runLegacyClient")
        private const val teaKitPropertyPrefix = "teakit."
        private val knownLoaders = listOf("fabric", "forge", "neoforge")
    }
}
