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
mod_name=Test Mod
mod_id=testmod
platform_minecraft_version=1.21.11
java_version=21
game_versions=1.21.11
release_type=beta
dry_run=true
mod_environment=both
modrinth_id=test-mod
curse_id=123456
mod_modrinth_depends=
mod_curse_depends=
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
            .withArguments('publishingRelease', '--stacktrace', '-Pdry_run=true')
            .build()

        then:
        result.task(':publishingAssemble').outcome == TaskOutcome.SUCCESS
        result.task(':publishingPublish').outcome == TaskOutcome.SUCCESS
        result.task(':publishingRelease').outcome == TaskOutcome.SUCCESS
        result.output.contains('[Publishing] Modrinth dryRun payload')
        result.output.contains('[Publishing] CurseForge dryRun payload')
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
