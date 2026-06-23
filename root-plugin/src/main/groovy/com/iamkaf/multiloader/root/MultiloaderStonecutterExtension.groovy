package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.MultiloaderTargetScope
import org.gradle.api.Project

class MultiloaderStonecutterExtension {
    static final String ACTIVE_PROPERTY = 'multiloader.stonecutter.active'

    private final Project project

    MultiloaderStonecutterExtension(Project project) {
        this.project = project
    }

    String active(String defaultVersion) {
        def explicitActive = optionalProperty(ACTIVE_PROPERTY)
        if (explicitActive != null) {
            return explicitActive
        }

        def targetVersions = optionalProperty(MultiloaderTargetScope.VERSIONS_PROPERTY)
        if (targetVersions != null && !targetVersions.equalsIgnoreCase('all')) {
            def firstTarget = csv(targetVersions).find { !it.equalsIgnoreCase('all') }
            if (firstTarget != null) {
                return firstTarget
            }
        }

        defaultVersion
    }

    private String optionalProperty(String name) {
        def value = project.findProperty(name)
        if (value == null) {
            return null
        }
        def stringValue = value.toString().trim()
        stringValue.isEmpty() ? null : stringValue
    }

    private static List<String> csv(String value) {
        value.split(',')
            .collect { it.trim() }
            .findAll { !it.isEmpty() }
            .unique()
    }
}
