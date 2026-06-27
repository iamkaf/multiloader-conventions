package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.StonecutterSourceLayout
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.Action
import org.gradle.api.Project

open class MultiloaderCommonExtension internal constructor(
    private val project: Project,
    private val minecraftVersion: String,
    private val rootName: String,
) {
    fun resourcesFrom(path: Any) {
        resourcesFrom(path) {}
    }

    fun resourcesFrom(path: Any, configure: Action<MultiloaderResourceLane>) {
        val lane = MultiloaderResourceLane(project, minecraftVersion, rootName, path.toString())
        configure.execute(lane)
        lane.apply()
    }
}

open class MultiloaderResourceLane internal constructor(
    private val project: Project,
    private val minecraftVersion: String,
    private val rootName: String,
    private val path: String,
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

        StonecutterSourceLayout.addCommonResourceLane(project, rootName, path)
    }
}
