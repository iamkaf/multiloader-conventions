package com.iamkaf.multiloader.publishing

import org.apache.maven.artifact.versioning.DefaultArtifactVersion

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

final class MultiloaderPublishRules {

    private MultiloaderPublishRules() {}

    static void requireNonEmpty(String label, Collection<?> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("[Publishing] ${label} is required")
        }
    }

    static void checkEmptyJar(File jarFile, List<String> loaders) {
        if (jarFile == null || !jarFile.exists()) {
            throw new IllegalStateException("[Publishing] Jar does not exist: ${jarFile}")
        }
        if (loaders == null || loaders.isEmpty()) {
            return
        }

        FileSystem fs = FileSystems.newFileSystem(jarFile.toPath(), (ClassLoader) null)
        try {
            Path quiltJson = fs.getPath('quilt.mod.json')
            Path fabricJson = fs.getPath('fabric.mod.json')
            Path forgeToml = fs.getPath('META-INF/mods.toml')
            Path neoforgeToml = fs.getPath('META-INF/neoforge.mods.toml')
            Path forgeMc = fs.getPath('mcmod.info')

            if ((loaders.contains('forge') || loaders.contains('neoforge')) && !Files.exists(neoforgeToml) && !Files.exists(forgeToml) && !Files.exists(forgeMc)) {
                throw new IllegalStateException('[Publishing] File marked as forge/neoforge, but no neoforge.mods.toml, mods.toml, or mcmod.info file was found')
            }

            if (loaders.contains('fabric') && !Files.exists(fabricJson)) {
                throw new IllegalStateException('[Publishing] File marked as fabric, but no fabric.mod.json file was found')
            }

            if (loaders.contains('quilt') && !Files.exists(quiltJson) && !Files.exists(fabricJson)) {
                throw new IllegalStateException('[Publishing] File marked as quilt, but no quilt.mod.json or fabric.mod.json file was found')
            }
        } finally {
            fs.close()
        }
    }

    static List<String> modrinthNormalizeGameVersions(List<String> gameVersions) {
        if (gameVersions == null) {
            return []
        }
        gameVersions
            .findAll { it != null && !it.trim().isEmpty() }
            .collect { it.trim() }
            .findAll { !it.endsWith('-snapshot') }
            .collect { it.toLowerCase(Locale.ROOT) }
    }

    static List<String> modrinthNormalizeLoaders(List<String> loaders) {
        if (loaders == null) {
            return []
        }

        def out = [] as List<String>
        loaders.each { loaderValue ->
            if (loaderValue == null) {
                return
            }

            def loader = loaderValue.toString().trim()
            if (loader.isEmpty()) {
                return
            }

            if (loader.equalsIgnoreCase("risugami's modloader")) {
                if (!out.contains('modloader')) {
                    out.add('modloader')
                }
                return
            }

            out.add(loader.toLowerCase(Locale.ROOT))
        }

        out
    }

    static List<String> curseNormalizeGameVersions(List<String> gameVersions) {
        if (gameVersions == null) {
            return []
        }

        def out = [] as List<String>
        def pattern = ~/[A-Za-z0-9]+/

        gameVersions.each { versionValue ->
            if (versionValue == null) {
                return
            }

            def version = versionValue.toString().trim()
            if (version.isEmpty() || (version =~ pattern).matches()) {
                return
            }

            if (version ==~ /.*-snapshot-\d+$/) {
                out.add(version.replaceAll(/-snapshot-\d+$/, '-snapshot'))
                return
            }

            if (version.contains('-pre') || version.contains('-rc')) {
                return
            }

            def min = new DefaultArtifactVersion('b1.6.6')
            def current = new DefaultArtifactVersion(version)
            if (current.compareTo(min) < 0) {
                out.add('beta 1.6.6')
            } else if (version.contains('b1')) {
                out.add(version.replace('b', 'beta '))
            } else {
                out.add(version)
            }
        }

        out
    }

    static List<String> curseNormalizeLoaders(List<String> loaders) {
        if (loaders == null) {
            return []
        }

        def out = [] as List<String>
        loaders.each { loaderValue ->
            if (loaderValue == null) {
                return
            }

            def loader = loaderValue.toString()
            if (loader.equalsIgnoreCase('modloader')) {
                out.add("risugami's modloader")
                return
            }

            if (loader.equalsIgnoreCase('flint')) {
                out.add('flint loader')
                return
            }

            out.add(loader)
        }

        out
    }
}
