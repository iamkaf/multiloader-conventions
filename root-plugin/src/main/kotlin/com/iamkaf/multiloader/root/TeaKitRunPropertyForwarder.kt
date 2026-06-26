package com.iamkaf.multiloader.root

import org.gradle.api.Project

object TeaKitRunPropertyForwarder {
    private val runTaskNames = setOf("runClient", "runLegacyClient")
    private const val propertyPrefix = "teakit."

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
            if (key.startsWith(propertyPrefix) && value != null) {
                properties[key] = value.toString()
            }
        }

        System.getProperties().stringPropertyNames()
            .filter { it.startsWith(propertyPrefix) }
            .sorted()
            .forEach { key ->
                System.getProperty(key)?.let { value -> properties[key] = value }
            }

        return properties
    }
}
