package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

open class MultiloaderCommonExtension internal constructor(
    private val project: Project,
    private val minecraftVersion: String,
) {
    fun resourcesFrom(path: Any) {
        resourcesFrom(path) {}
    }

    fun resourcesFrom(path: Any, configure: Action<MultiloaderResourceLane>) {
        val lane = MultiloaderResourceLane(project, minecraftVersion, path)
        configure.execute(lane)
        lane.apply()
    }
}

open class MultiloaderResourceLane internal constructor(
    private val project: Project,
    private val minecraftVersion: String,
    private val path: Any,
) {
    private var minimumMinecraftVersion: String? = null

    fun minecraftAtLeast(version: String) {
        minimumMinecraftVersion = version
    }

    internal fun apply() {
        val minimum = minimumMinecraftVersion
        if (minimum != null && !VersionPolicy.isMinecraftVersionAtLeast(minecraftVersion, minimum)) {
            return
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.named("main").configure {
            resources.srcDir(project.file(path))
        }
    }
}
