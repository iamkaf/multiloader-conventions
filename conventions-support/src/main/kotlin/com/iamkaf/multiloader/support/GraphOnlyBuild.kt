package com.iamkaf.multiloader.support

import org.gradle.api.Project

/** Builds that only write or print the multiloader graph skip loader toolchains, so some tasks never exist. */
object GraphOnlyBuild {
    /** Loom and ModDevGradle configurations that consumer build scripts add dependencies to by name. */
    private val loaderDependencyScopes = listOf(
        "modApi",
        "modImplementation",
        "modCompileOnly",
        "modRuntimeOnly",
        "modLocalRuntime",
        "localRuntime",
        "include",
    )

    @JvmStatic
    fun requested(project: Project): Boolean {
        val taskNames = project.gradle.startParameter.taskNames
        return taskNames.isNotEmpty() && taskNames.all { taskName ->
            val requested = taskName.split(":").last()
            requested == "writeMultiloaderGraph" || requested == "printMultiloaderGraph"
        }
    }

    /**
     * Configures a Stonecutter node without its loader toolchain, so no Minecraft setup runs. The node keeps what the
     * graph reporter and publishing planner read: coordinates, archive name, Java version, and the Maven publication.
     * [placeholderTasks] stand in for toolchain tasks they look up by name.
     */
    @JvmStatic
    fun configureNode(
        project: Project,
        context: MultiloaderProjectContext,
        role: MultiloaderProjectRole,
        placeholderTasks: List<String>,
    ) {
        val identity = ProjectIdentity.from(context, role)
        identity.applyCoordinates(project)
        JavaProjectWiring.configureArchiveName(project, identity.archiveName)

        StonecutterSourceLayout.configureGraphOnly(project)
        JavaProjectWiring.configureJavaBuild(project, identity.javaVersion)
        JavaProjectWiring.configureArchiveAndResourceDefaults(project)

        loaderDependencyScopes.forEach(project.configurations::dependencyScope)
        placeholderTasks.forEach { name ->
            project.tasks.register(name) {
                description = "Placeholder for the $name task in graph-only ${identity.minecraftVersion} builds."
            }
        }

        MavenPublicationWiring.configureJavaComponentPublication(project, context)
    }
}
