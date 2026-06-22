package com.iamkaf.multiloader.support

import org.gradle.api.GradleException

import java.util.Properties

class BuildToolsVersions {
    private static final String RESOURCE = '/com/iamkaf/multiloader/support/build-tools.properties'
    private static final Properties VERSIONS = loadVersions()

    static String required(String name) {
        def value = VERSIONS.getProperty(name)
        if (value == null || value.isBlank()) {
            throw new GradleException("Missing build-tools version '${name}'")
        }
        value
    }

    private static Properties loadVersions() {
        def properties = new Properties()
        def stream = BuildToolsVersions.getResourceAsStream(RESOURCE)
        if (stream == null) {
            throw new GradleException("Missing build-tools version resource '${RESOURCE}'")
        }
        stream.withCloseable {
            properties.load(it)
        }
        properties
    }
}
