package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.MultiloaderTargetScope
import com.iamkaf.multiloader.support.PublicationArtifactStrategy
import com.iamkaf.multiloader.support.VersionPolicy
import com.iamkaf.multiloader.support.adapters.ArchiveTaskAdapter
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File

object HorizontalMergeTasks {
    private const val FORGIX_COORDINATE = "io.github.pacifistmc.forgix:Forgix:2.0.0-SNAPSHOT.5.1"
    private const val TOOL_CONFIGURATION = "multiloaderHorizontalMergeTool"
    private val loaderOrder = listOf("fabric", "forge", "neoforge")

    fun register(
        project: Project,
        extension: MultiloaderArtifactsExtension,
        versionDirs: List<File>,
        targetScope: MultiloaderTargetScope,
    ) {
        project.gradle.projectsEvaluated {
            val horizontal = extension.getHorizontalMerge()
            if (!horizontal.enabled.get()) return@projectsEvaluated

            val configuredVersions = horizontal.versions.getOrElse(emptySet())
            val allowedUnstable = horizontal.allowUnstableVersions.getOrElse(emptySet())
            HorizontalMergePolicy.requireKnownUnstableVersions(allowedUnstable)
            requireKnownVersions(configuredVersions, versionDirs)

            val plans = versionDirs
                .filter { targetScope.includesVersion(it.name) }
                .filter { configuredVersions.isEmpty() || it.name in configuredVersions }
                .mapNotNull { dir -> planVersion(project, dir, targetScope, allowedUnstable) }

            val mergeAll = project.tasks.register("mergeHorizontalJars") {
                group = "horizontal artifacts"
                description = "Merges one multi-loader jar for every selected Minecraft version."
            }
            val validateAll = project.tasks.register("validateHorizontalJars") {
                group = "verification"
                description = "Validates every selected horizontal multi-loader jar."
            }

            if (plans.isEmpty()) {
                mergeAll.configure {
                    doLast {
                        project.logger.lifecycle(
                            "[Horizontal Merge] No complete multi-loader version is available in the active target scope.",
                        )
                    }
                }
                return@projectsEvaluated
            }

            val tool = createToolConfiguration(project)
            plans.forEach { plan ->
                val taskSuffix = RootTaskNames.taskSuffix(plan.minecraftVersion)
                val mergeTask = registerMergeTask(project, plan, taskSuffix, tool)
                val validateTask = registerValidationTask(project, plan, taskSuffix, mergeTask)
                mergeAll.configure { dependsOn(mergeTask) }
                validateAll.configure { dependsOn(validateTask) }
            }
        }
    }

    private fun planVersion(
        root: Project,
        versionDir: File,
        targetScope: MultiloaderTargetScope,
        allowedUnstable: Set<String>,
    ): HorizontalMergePlan? {
        val properties = RootVersionMatrix.versionMetadata(versionDir)
        val minecraftVersion = properties.getProperty("project.minecraft") ?: versionDir.name
        val projectVersion = properties.getProperty("project.version")
            ?: throw GradleException("Missing project.version for horizontal merge $minecraftVersion")
        val configuredLoaders = RootVersionMatrix.parseEnabledLoaders(properties)
            .filter { it in loaderOrder }
            .let { loaders -> loaderOrder.filter { it in loaders } }
        val selectedLoaders = targetScope.loadersFor(versionDir.name)
            .let { loaders -> loaderOrder.filter { it in loaders } }

        if (configuredLoaders.size < 2) {
            root.logger.lifecycle(
                "[Horizontal Merge] Skipping $minecraftVersion because it enables fewer than two loaders.",
            )
            return null
        }
        if (selectedLoaders != configuredLoaders) {
            root.logger.lifecycle(
                "[Horizontal Merge] Skipping $minecraftVersion because the active loader target is partial " +
                    "(selected=${selectedLoaders.joinToString(",")}, required=${configuredLoaders.joinToString(",")}).",
            )
            return null
        }

        val tier = HorizontalMergePolicy.requireSupported(minecraftVersion, allowedUnstable)
        if (tier == HorizontalMergeTier.UNSTABLE_RELOCATED) {
            root.logger.warn(
                "[Horizontal Merge] Minecraft $minecraftVersion is an acknowledged unstable tier: " +
                    "common class names may be loader-relocated and addon mixins may break.",
            )
        }

        val archives = configuredLoaders.associateWith { loader ->
            resolveArchive(root, versionDir.name, minecraftVersion, loader)
        }
        val modId = requiredRootProperty(root, "mod.id")
        val output = root.layout.buildDirectory.file(
            "libs/horizontal/$minecraftVersion/$modId-$projectVersion.jar",
        )
        return HorizontalMergePlan(minecraftVersion, projectVersion, modId, configuredLoaders, tier, archives, output)
    }

    private fun resolveArchive(
        root: Project,
        projectVersionName: String,
        minecraftVersion: String,
        loader: String,
    ): LoaderArchive {
        val target = root.findProject(":$loader:$projectVersionName")
            ?: throw GradleException("Missing horizontal merge project :$loader:$projectVersionName")
        val strategy = when (loader) {
            "fabric" -> VersionPolicy.fabricPublicationArtifact(minecraftVersion)
            "forge" -> VersionPolicy.forgePublicationArtifact()
            "neoforge" -> VersionPolicy.neoForgePublicationArtifact()
            else -> throw GradleException("Unknown horizontal merge loader '$loader'")
        }
        val archiveTask = findArchiveTask(target, strategy)
        // Some modern Forge projects produce the distributable archive directly from `jar`
        // and do not register ForgeGradle's historical `reobfJar` lifecycle task. The archive
        // task is the required input; supplementary lifecycle tasks are dependencies only when
        // the selected loader plugin actually exposes them.
        val buildTasks = strategy.buildTasks.mapNotNull(target.tasks::findByName)
        return LoaderArchive(
            loader,
            target,
            archiveTask,
            buildTasks,
            ArchiveTaskAdapter.archiveFile(target, archiveTask),
        )
    }

    private fun findArchiveTask(project: Project, strategy: PublicationArtifactStrategy): Task {
        project.tasks.findByName(strategy.artifactTask)?.let { return it }
        strategy.fallbackArtifactTask?.let { fallback ->
            project.tasks.findByName(fallback)?.let { return it }
        }
        throw GradleException(
            "Missing archive task for ${project.path} " +
                "(preferred=${strategy.artifactTask}, fallback=${strategy.fallbackArtifactTask})",
        )
    }

    private fun registerMergeTask(
        project: Project,
        plan: HorizontalMergePlan,
        taskSuffix: String,
        tool: Configuration,
    ): TaskProvider<ForgixHorizontalMergeTask> =
        project.tasks.register("mergeHorizontalJar$taskSuffix", ForgixHorizontalMergeTask::class.java) {
            group = "horizontal artifacts"
            description = "Merges the ${plan.minecraftVersion} ${plan.loaders.joinToString("/")} jars."
            minecraftVersion.set(plan.minecraftVersion)
            enabledLoaders.set(plan.loaders)
            mergerClasspath.from(tool)
            archiveFile.set(plan.output)
            workingDirectory.set(project.layout.buildDirectory.dir("tmp/horizontalMerge/${plan.minecraftVersion}"))

            plan.archives.values.forEach { archive ->
                dependsOn(archive.archiveTask)
                dependsOn(archive.buildTasks)
                setInputJar(this, archive.loader, archive.archiveFile)
            }
        }

    private fun registerValidationTask(
        project: Project,
        plan: HorizontalMergePlan,
        taskSuffix: String,
        mergeTask: TaskProvider<ForgixHorizontalMergeTask>,
    ): TaskProvider<HorizontalJarValidationTask> =
        project.tasks.register("validateHorizontalJar$taskSuffix", HorizontalJarValidationTask::class.java) {
            group = "verification"
            description = "Validates the ${plan.minecraftVersion} horizontal jar structure."
            dependsOn(mergeTask)
            archiveFile.set(mergeTask.flatMap { it.archiveFile })
            enabledLoaders.set(plan.loaders)
            modId.set(plan.modId)
            tier.set(plan.tier)
            validationReport.set(
                project.layout.buildDirectory.file("reports/horizontalMerge/${plan.minecraftVersion}.txt"),
            )
            plan.archives.values.forEach { archive ->
                setInputJar(this, archive.loader, archive.archiveFile)
            }
        }

    private fun setInputJar(
        task: ForgixHorizontalMergeTask,
        loader: String,
        archive: Provider<RegularFile>,
    ) {
        when (loader) {
            "fabric" -> task.fabricJar.set(archive)
            "forge" -> task.forgeJar.set(archive)
            "neoforge" -> task.neoForgeJar.set(archive)
        }
    }

    private fun setInputJar(
        task: HorizontalJarValidationTask,
        loader: String,
        archive: Provider<RegularFile>,
    ) {
        when (loader) {
            "fabric" -> task.fabricJar.set(archive)
            "forge" -> task.forgeJar.set(archive)
            "neoforge" -> task.neoForgeJar.set(archive)
        }
    }

    private fun createToolConfiguration(project: Project): Configuration {
        project.repositories.gradlePluginPortal {
            content { includeGroup("io.github.pacifistmc.forgix") }
        }

        return project.configurations.create(TOOL_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
            description = "Pinned Forgix CLI used only by opt-in horizontal merge tasks."
        }.also { configuration ->
            project.dependencies.add(configuration.name, FORGIX_COORDINATE)
        }
    }

    private fun requireKnownVersions(configuredVersions: Set<String>, versionDirs: List<File>) {
        if (configuredVersions.isEmpty()) return
        val known = versionDirs.map(File::getName).toSet()
        val unknown = configuredVersions - known
        if (unknown.isNotEmpty()) {
            throw GradleException("Unknown horizontal merge versions: ${unknown.sorted().joinToString(", ")}")
        }
    }

    private fun requiredRootProperty(project: Project, name: String): String {
        val value = project.findProperty(name)?.toString()?.trim()
        if (value.isNullOrEmpty()) throw GradleException("Missing required Gradle property '$name'")
        return value
    }
}

private data class HorizontalMergePlan(
    val minecraftVersion: String,
    val projectVersion: String,
    val modId: String,
    val loaders: List<String>,
    val tier: HorizontalMergeTier,
    val archives: Map<String, LoaderArchive>,
    val output: Provider<RegularFile>,
)

private data class LoaderArchive(
    val loader: String,
    val project: Project,
    val archiveTask: Task,
    val buildTasks: List<Task>,
    val archiveFile: Provider<RegularFile>,
)
