package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import java.util.Properties

object BuildToolsVersions {
    private const val RESOURCE = "/com/iamkaf/multiloader/support/build-tools.properties"
    private val versions: Properties = loadVersions()

    @JvmStatic
    fun required(name: String): String {
        val value = versions.getProperty(name)
        if (value.isNullOrBlank()) {
            throw GradleException("Missing build-tools version '$name'")
        }
        return value
    }

    private fun loadVersions(): Properties {
        val stream = BuildToolsVersions::class.java.getResourceAsStream(RESOURCE)
            ?: throw GradleException("Missing build-tools version resource '$RESOURCE'")
        return Properties().also { properties ->
            stream.use { properties.load(it) }
        }
    }
}
