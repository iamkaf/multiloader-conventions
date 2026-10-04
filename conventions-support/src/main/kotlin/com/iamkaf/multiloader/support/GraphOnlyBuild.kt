package com.iamkaf.multiloader.support

import org.gradle.api.Project

/** Builds that only write or print the multiloader graph skip loader toolchains, so some tasks never exist. */
object GraphOnlyBuild {
    @JvmStatic
    fun requested(project: Project): Boolean {
        val taskNames = project.gradle.startParameter.taskNames
        return taskNames.isNotEmpty() && taskNames.all { taskName ->
            val requested = taskName.split(":").last()
            requested == "writeMultiloaderGraph" || requested == "printMultiloaderGraph"
        }
    }
}
