package com.iamkaf.multiloader.publishing

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderPublishingPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def setup() {
        new File(testProjectDir, 'settings.gradle').text = '''
rootProject.name = 'publishing-test'
include('fabric', 'forge', 'neoforge')
'''.stripIndent()

        new File(testProjectDir, 'changelog.md').text = '''
# Changelog

## 1.2.3
- Added dry-run publishing coverage.

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

        new File(testProjectDir, 'build.gradle').text = '''
plugins {
    id 'com.iamkaf.multiloader.publishing'
}

group = 'com.example'
version = '1.2.3'
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
        result.output.contains('"loaders": [\n            "fabric"\n        ]')
        result.output.contains('"loaders": [\n            "forge"\n        ]')
        result.output.contains('"loaders": [\n            "neoforge"\n        ]')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "fabric"\n        ]')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "forge"\n        ]')
        result.output.contains('"gameVersions": [\n            "1.21.11",\n            "client",\n            "server",\n            "Java 21",\n            "neoforge"\n        ]')
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

    private void createLoaderProject(String name, String metadataContents, String metadataPath) {
        def dir = new File(testProjectDir, name)
        dir.mkdirs()
        new File(dir, 'build.gradle').text = """
plugins {
  id 'java'
}

group = 'com.example'
version = '1.2.3'

tasks.named('jar') {
  archiveBaseName.set('testmod-${name}')
}
""".stripIndent()

        def metadata = new File(dir, "src/main/resources/${metadataPath}")
        metadata.parentFile.mkdirs()
        metadata.text = metadataContents
    }
}
