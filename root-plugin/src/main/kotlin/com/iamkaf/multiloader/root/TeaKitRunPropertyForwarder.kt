package com.iamkaf.multiloader.root

import org.gradle.api.Project

object TeaKitRunPropertyForwarder {
    private val runTaskNames = setOf("runClient", "runLegacyClient")
    private const val propertyPrefix = "teakit."

    /**
     * Non-`teakit.*` system properties that TeaKit is allowed to forward into
     * the game JVM. This is an explicit allowlist, not a broad pass-through.
     *
     * `fabric.noGui` suppresses Fabric Loader's own Swing/AWT error GUI on
     * Fabric Loader 0.19.x so launch failures exit instead of blocking the JVM.
     */
    private val allowedNonTeaKit = setOf("fabric.noGui")

    fun configure(project: Project) {
        project.subprojects {
            tasks.configureEach {
                if (name !in runTaskNames) return@configureEach
                val systemPropertyMethod = javaClass.methods.firstOrNull { method ->
                    method.name == "systemProperty" && method.parameterCount == 2
                } ?: return@configureEach

                collectSystemProperties(project).forEach { (propertyName, value) ->
                    systemPropertyMethod.invoke(this, propertyName, value)
                }
            }
        }
    }

    fun collectSystemProperties(project: Project): Map<String, String> {
        val properties = linkedMapOf<String, String>()
        project.gradle.startParameter.systemPropertiesArgs.forEach { (key, value) ->
            if (isForwardable(key)) {
                properties[key] = value
            }
        }

        System.getProperties().stringPropertyNames()
            .filter { isForwardable(it) }
            .sorted()
            .forEach { key ->
                System.getProperty(key)?.let { value -> properties[key] = value }
            }

        return properties
    }

    private fun isForwardable(key: String): Boolean =
        key.startsWith(propertyPrefix) || allowedNonTeaKit.contains(key)
}
