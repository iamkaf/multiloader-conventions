package com.iamkaf.multiloader.publishing

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderPublishingPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def setup() {
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "publishing-test"
include("fabric", "forge", "neoforge")
'''.stripIndent()

        new File(testProjectDir, 'changelog.md').text = '''
# Changelog

## 2.0.0
- This newer release must not be used for 1.2.3 artifacts.

## 1.2.3
- Added dry-run publishing coverage.
- 1.21.11: Added old-line publish notes.
- 26.2: This line is for a different Minecraft version.

## Types of changes
- Added for new features.
'''.stripIndent()

        new File(testProjectDir, 'gradle.properties').text = '''
project.minecraft=1.21.11
project.java=21
mod.name=Test Mod
mod.id=testmod
publish.game-versions=1.21.11
publish.release-type=beta
publish.dry-run=true
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.publishing")
}

group = "com.example"
version = "1.2.3"
'''.stripIndent()

        createLoaderProject('fabric', '{"schemaVersion":1,"id":"testmod","version":"1.2.3"}', 'fabric.mod.json')
        createLoaderProject('forge', '[mods]\\n[[mods]]\\nmodId=\"testmod\"\\n', 'META-INF/mods.toml')
        createLoaderProject('neoforge', 'modLoader="javafml"\\n[[mods]]\\nmodId="testmod"\\n', 'META-INF/neoforge.mods.toml')
    }

    def "publishingRelease performs a dry-run publish"() {
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('publishingRelease', '--stacktrace')
            .build()

        then:
        result.task(':publishingRelease').outcome == TaskOutcome.SUCCESS
        result.output.contains('[Publishing] Copied :fabric ->')
        result.output.contains('[Publishing] Copied :forge ->')
        result.output.contains('[Publishing] Copied :neoforge ->')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-fabric-1.2.3.jar)')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-forge-1.2.3.jar)')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-neoforge-1.2.3.jar)')
        result.output.contains('[Publishing] CurseForge dryRun payload (testmod-fabric-1.2.3.jar)')
        result.output.contains('[Publishing] CurseForge dryRun payload (testmod-forge-1.2.3.jar)')
        result.output.contains('[Publishing] CurseForge dryRun payload (testmod-neoforge-1.2.3.jar)')
        result.output.contains('"displayName": "testmod-fabric-1.2.3"')
        result.output.contains('"displayName": "testmod-forge-1.2.3"')
        result.output.contains('"displayName": "testmod-neoforge-1.2.3"')
        result.output.contains('"name": "testmod-fabric-1.2.3"')
        result.output.contains('"name": "testmod-forge-1.2.3"')
        result.output.contains('"name": "testmod-neoforge-1.2.3"')
        result.output.contains('Added dry-run publishing coverage.')
        result.output.contains('1.21.11: Added old-line publish notes.')
        !result.output.contains('This newer release must not be used')
        !result.output.contains('This line is for a different Minecraft version')
        result.output.contains('"loaders": [\n            "fabric"\n        ]')
        result.output.contains('"loaders": [\n            "forge"\n        ]')
        result.output.contains('"loaders": [\n            "neoforge"\n        ]')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "fabric"\n        ]')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "forge"\n        ]')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "neoforge"\n        ]')
    }

    def "final changelog file overrides source extraction"() {
        given:
        def finalChangelog = new File(testProjectDir, 'final-changelog.md')
        finalChangelog.text = '''
# Final

- Exact console-selected text.
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('publishModrinthFabric', "-Ppublish.changelog.final-file=${finalChangelog.absolutePath}", '--stacktrace')
            .build()

        then:
        result.task(':publishModrinthFabric').outcome == TaskOutcome.SUCCESS
        result.output.contains('Exact console-selected text.')
        !result.output.contains('Added dry-run publishing coverage.')
    }

    def "missing matching changelog heading fails clearly"() {
        given:
        new File(testProjectDir, 'changelog.md').text = '''
# Changelog

## 9.9.9
- Wrong release.

## Types of changes
- Added for new features.
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('publishModrinthFabric', '--stacktrace')
            .buildAndFail()

        then:
        result.output.contains('Failed to extract changelog 1.2.3')
    }

    def "individual loader platform tasks only publish their own artifact"() {
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('publishCurseforgeFabric', '--stacktrace')
            .build()

        then:
        result.task(':publishingAssembleFabric').outcome == TaskOutcome.SUCCESS
        result.task(':publishCurseforgeFabric').outcome == TaskOutcome.SUCCESS
        result.output.contains('[Publishing] CurseForge dryRun payload (testmod-fabric-1.2.3.jar)')
        !result.output.contains('[Publishing] CurseForge dryRun payload (testmod-forge-1.2.3.jar)')
        !result.output.contains('[Publishing] CurseForge dryRun payload (testmod-neoforge-1.2.3.jar)')
        !result.output.contains('[Publishing] Modrinth dryRun payload')
    }

    def "publishingRelease skips disabled loaders from project.enabled-loaders"() {
        given:
        new File(testProjectDir, 'gradle.properties').text = '''
project.minecraft=26.1.2
project.java=25
project.enabled-loaders=fabric,neoforge
mod.name=Test Mod
mod.id=testmod
publish.game-versions=26.1.2
publish.release-type=release
publish.dry-run=true
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('publishingRelease', '--stacktrace')
            .build()

        then:
        result.task(':publishingRelease').outcome == TaskOutcome.SUCCESS
        result.task(':publishingAssembleFabric').outcome == TaskOutcome.SUCCESS
        result.task(':publishingAssembleNeoforge').outcome == TaskOutcome.SUCCESS
        result.task(':publishingAssembleForge').outcome == TaskOutcome.SKIPPED
        result.output.contains('[Publishing] Copied :fabric ->')
        result.output.contains('[Publishing] Copied :neoforge ->')
        !result.output.contains('[Publishing] Copied :forge ->')
        result.output.contains('[Publishing] CurseForge dryRun payload (testmod-fabric-1.2.3.jar)')
        result.output.contains('[Publishing] CurseForge dryRun payload (testmod-neoforge-1.2.3.jar)')
        !result.output.contains('[Publishing] CurseForge dryRun payload (testmod-forge-1.2.3.jar)')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-fabric-1.2.3.jar)')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-neoforge-1.2.3.jar)')
        !result.output.contains('[Publishing] Modrinth dryRun payload (testmod-forge-1.2.3.jar)')
    }

    def "platform dependencies can come from active version properties"() {
        given:
        new File(testProjectDir, 'gradle.properties').text = '''
mod.name=Test Mod
mod.id=testmod
publish.release-type=release
publish.dry-run=true
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        def versionProperties = new File(testProjectDir, 'versions/26.2/gradle.properties')
        versionProperties.parentFile.mkdirs()
        versionProperties.text = '''
project.minecraft=26.2
project.java=25
project.enabled-loaders=fabric
publish.game-versions=26.2
dependencies.modrinth.required=amber,konfig
dependencies.curseforge.required=amber-lib,konfig
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments(
                'publishModrinthFabric',
                'publishCurseforgeFabric',
                '-Pmultiloader.stonecutter.active=26.2',
                '--stacktrace',
            )
            .build()

        then:
        result.task(':publishModrinthFabric').outcome == TaskOutcome.SUCCESS
        result.task(':publishCurseforgeFabric').outcome == TaskOutcome.SUCCESS
        result.output.contains('"project_id": "amber"')
        result.output.contains('"project_id": "konfig"')
        result.output.contains('"dependency_type": "required"')
        result.output.contains('"relations": {')
        result.output.contains('"slug": "amber-lib"')
        result.output.contains('"slug": "konfig"')
        result.output.contains('"type": "requiredDependency"')
    }

    def "explicit publications support matrix artifacts with per-project game and java versions"() {
        given:
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "publishing-matrix-test"
include("fabric:1.21.11", "neoforge:26.1.2")
'''.stripIndent()

        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.publishing")
}

group = "com.example"
version = "1.2.3"

multiloaderPublishing {
    publication("fabric-1.21.11") {
        project(":fabric:1.21.11")
        loader("fabric")
    }
    publication("neoforge-26.1.2") {
        project(":neoforge:26.1.2")
        loader("neoforge")
    }
}
'''.stripIndent()

        new File(testProjectDir, 'gradle.properties').text = '''
mod.name=Test Mod
mod.id=testmod
publish.release-type=beta
publish.dry-run=true
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        createNestedLoaderProject('fabric/1.21.11', 'testmod-fabric', '{"schemaVersion":1,"id":"testmod","version":"1.2.3"}', 'fabric.mod.json', '1.21.11', '21')
        createNestedLoaderProject('neoforge/26.1.2', 'testmod-neoforge', 'modLoader="javafml"\\n[[mods]]\\nmodId="testmod"\\n', 'META-INF/neoforge.mods.toml', '26.1.2', '25')

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('publishingRelease', '--stacktrace')
            .build()

        then:
        result.task(':publishingRelease').outcome == TaskOutcome.SUCCESS
        result.output.contains('[Publishing] Copied :fabric:1.21.11 ->')
        result.output.contains('[Publishing] Copied :neoforge:26.1.2 ->')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-fabric-1.2.3.jar)')
        result.output.contains('[Publishing] Modrinth dryRun payload (testmod-neoforge-1.2.3.jar)')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "fabric"\n        ]')
        result.output.contains('"gameVersions": [\n            "26.1.2",\n            "client",\n            "server",\n            "Java 25",\n            "neoforge"\n        ]')
    }

    private void createLoaderProject(String name, String metadataContents, String metadataPath) {
        def dir = new File(testProjectDir, name)
        dir.mkdirs()
        new File(dir, 'build.gradle.kts').text = """
plugins {
    java
}

group = "com.example"
version = "1.2.3"

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveBaseName.set("testmod-${name}")
}
""".stripIndent()

        def metadata = new File(dir, "src/main/resources/${metadataPath}")
        metadata.parentFile.mkdirs()
        metadata.text = metadataContents
    }

    private void createNestedLoaderProject(String path, String archiveBaseName, String metadataContents, String metadataPath, String minecraftVersion, String javaVersion) {
        def dir = new File(testProjectDir, path)
        dir.mkdirs()
        new File(dir, 'build.gradle.kts').text = """
plugins {
    java
}

group = "com.example"
version = "1.2.3"
extra["project.minecraft"] = "${minecraftVersion}"
extra["project.java"] = "${javaVersion}"

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveBaseName.set("${archiveBaseName}")
}
""".stripIndent()

        def metadata = new File(dir, "src/main/resources/${metadataPath}")
        metadata.parentFile.mkdirs()
        metadata.text = metadataContents
    }
}
