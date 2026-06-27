package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.MultiloaderTargetScope
import org.gradle.api.Project

open class MultiloaderStonecutterExtension(private val project: Project) {
    companion object {
        const val ACTIVE_PROPERTY = "multiloader.stonecutter.active"
    }

    fun active(defaultVersion: String): String {
        optionalProperty(ACTIVE_PROPERTY)?.let { return it }

        val targetVersions = optionalProperty(MultiloaderTargetScope.VERSIONS_PROPERTY)
        if (targetVersions != null && !targetVersions.equals("all", ignoreCase = true)) {
            csv(targetVersions).firstOrNull { !it.equals("all", ignoreCase = true) }?.let { return it }
        }

        return defaultVersion
    }

    private fun optionalProperty(name: String): String? {
        val value = project.findProperty(name) ?: return null
        return value.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun csv(value: String): List<String> =
        value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
