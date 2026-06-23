package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.Settings

class MultiloaderTargetScope {
    static final String VERSIONS_PROPERTY = 'multiloader.target.versions'
    static final String LOADERS_PROPERTY = 'multiloader.target.loaders'
    static final List<String> KNOWN_LOADERS = ['fabric', 'forge', 'neoforge']

    final List<String> versions
    final Map<String, List<String>> loadersByVersion
    final boolean constrained

    private MultiloaderTargetScope(List<String> versions, Map<String, List<String>> loadersByVersion, boolean constrained) {
        this.versions = versions.asImmutable()
        this.loadersByVersion = loadersByVersion.collectEntries { version, loaders ->
            [(version): loaders.asImmutable()]
        }.asImmutable()
        this.constrained = constrained
    }

    static MultiloaderTargetScope fromSettings(Settings settings, Map<String, List<String>> enabledLoadersByVersion) {
        of(
            settings.providers.gradleProperty(VERSIONS_PROPERTY).orNull,
            settings.providers.gradleProperty(LOADERS_PROPERTY).orNull,
            enabledLoadersByVersion,
        )
    }

    static MultiloaderTargetScope fromProject(Project project, Map<String, List<String>> enabledLoadersByVersion) {
        of(
            optionalProperty(project, VERSIONS_PROPERTY),
            optionalProperty(project, LOADERS_PROPERTY),
            enabledLoadersByVersion,
        )
    }

    boolean includesVersion(String version) {
        versions.contains(version)
    }

    List<String> loadersFor(String version) {
        loadersByVersion[version] ?: []
    }

    boolean includes(String version, String loader) {
        loadersFor(version).contains(loader)
    }

    private static MultiloaderTargetScope of(String rawVersions, String rawLoaders, Map<String, List<String>> enabledLoadersByVersion) {
        def availableVersions = enabledLoadersByVersion.keySet().toList()
        def requestedVersions = parseVersions(rawVersions, availableVersions)
        def requestedLoaders = parseLoaders(rawLoaders)
        def scoped = isPresent(rawVersions) || isPresent(rawLoaders)

        def loadersByVersion = [:] as LinkedHashMap<String, List<String>>
        requestedVersions.each { version ->
            def enabled = enabledLoadersByVersion[version] ?: []
            loadersByVersion[version] = requestedLoaders == null
                ? enabled
                : enabled.findAll { requestedLoaders.contains(it) }
        }

        new MultiloaderTargetScope(requestedVersions, loadersByVersion, scoped)
    }

    private static List<String> parseVersions(String raw, List<String> availableVersions) {
        if (!isPresent(raw) || raw.trim().equalsIgnoreCase('all')) {
            return availableVersions
        }

        def requested = csv(raw)
        def unknown = requested.findAll { !availableVersions.contains(it) }
        if (!unknown.isEmpty()) {
            throw new GradleException("Unknown ${VERSIONS_PROPERTY}: ${unknown.join(', ')}. Available: ${availableVersions.join(', ')}")
        }
        requested
    }

    private static List<String> parseLoaders(String raw) {
        if (!isPresent(raw) || raw.trim().equalsIgnoreCase('enabled')) {
            return null
        }

        def requested = csv(raw)
        def unknown = requested.findAll { !KNOWN_LOADERS.contains(it) }
        if (!unknown.isEmpty()) {
            throw new GradleException("Unknown ${LOADERS_PROPERTY}: ${unknown.join(', ')}. Available: ${KNOWN_LOADERS.join(', ')}")
        }
        requested
    }

    private static boolean isPresent(String value) {
        value != null && !value.trim().isEmpty()
    }

    private static List<String> csv(String value) {
        def parts = value.split(',')
            .collect { it.trim() }
            .findAll { !it.isEmpty() }
            .unique()
        if (parts.isEmpty()) {
            throw new GradleException('Target scope values must not be empty.')
        }
        parts
    }

    private static String optionalProperty(Project project, String name) {
        def value = project.findProperty(name)
        if (value == null) {
            return null
        }
        value.toString()
    }
}
