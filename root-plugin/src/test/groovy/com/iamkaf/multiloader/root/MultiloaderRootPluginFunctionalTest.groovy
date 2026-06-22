package com.iamkaf.multiloader.root

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderRootPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def setup() {
        new File(testProjectDir, 'settings.gradle').text = '''
rootProject.name = 'root-test'
include('common:26.1.2', 'common:26.2')
include('fabric:26.1.2', 'fabric:26.2')
include('forge:26.2')
include('neoforge:26.1.2', 'neoforge:26.2')
'''.stripIndent()

        new File(testProjectDir, 'build.gradle').text = '''
plugins {
    id 'com.iamkaf.multiloader.root'
}

group = 'com.example'
version = '9.9.9'
'''.stripIndent()

        new File(testProjectDir, 'changelog.md').text = '''
# Changelog

## 9.9.9
- Added graph metadata coverage.

## Types of changes
- Added for new features.
'''.stripIndent()

        new File(testProjectDir, 'gradle.properties').text = '''
project.group=com.example
project.version=9.9.9
project.plugins=2.3-SNAPSHOT
mod.id=graphmod
mod.name=Graph Mod
publish.dry-run=true
publish.modrinth.id=graph-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        new File(testProjectDir, 'teakit.toml').text = '''
[nodes."26.1.2-fabric"]
loader = "fabric"
minecraft = "26.1.2"
tasks = [":fabric:26.1.2:runClient"]

[nodes."26.2-forge"]
loader = "forge"
minecraft = "26.2"
tasks = [":forge:26.2:runClient"]
'''.stripIndent()

        writeVersion('26.1.2', 'fabric,neoforge')
        writeVersion('26.2', 'fabric,forge,neoforge')

        createProject('common/26.1.2', 'graphmod-common', '26.1.2', '25', false)
        createProject('common/26.2', 'graphmod-common', '26.2', '25', false)
        createProject('fabric/26.1.2', 'graphmod-fabric', '26.1.2', '25', true)
        createProject('fabric/26.2', 'graphmod-fabric', '26.2', '25', true)
        createProject('forge/26.2', 'graphmod-forge', '26.2', '25', true)
        createProject('neoforge/26.1.2', 'graphmod-neoforge', '26.1.2', '25', true)
        createProject('neoforge/26.2', 'graphmod-neoforge', '26.2', '25', true)
    }

    def "printMultiloaderGraph emits version loader task and artifact metadata"() {
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('printMultiloaderGraph', '--stacktrace')
            .build()

        def graph = extractGraph(result.output)
        def version2612 = graph.versions.find { it.name == '26.1.2' }
        def version262 = graph.versions.find { it.name == '26.2' }
        def fabric2612 = version2612.loaders.find { it.name == 'fabric' }
        def forge2612 = version2612.loaders.find { it.name == 'forge' }
        def forge262 = version262.loaders.find { it.name == 'forge' }

        then:
        result.task(':printMultiloaderGraph').outcome == TaskOutcome.SUCCESS
        graph.schemaVersion == 1
        graph.mod.id == 'graphmod'
        graph.mod.name == 'Graph Mod'
        graph.conventions.version == '2.3-SNAPSHOT'

        version2612.enabledLoaders == ['fabric', 'neoforge']
        version2612.common.projectPath == ':common:26.1.2'
        version2612.common.buildTask == ':common:26.1.2:build'

        fabric2612.enabled
        fabric2612.projectPath == ':fabric:26.1.2'
        fabric2612.buildTask == ':fabric:26.1.2:build'
        fabric2612.runClientTask == ':fabric:26.1.2:runClient'
        fabric2612.artifactTask == ':fabric:26.1.2:jar'
        fabric2612.artifactPath.endsWith('fabric/26.1.2/build/libs/graphmod-fabric-9.9.9+26.1.2.jar')
        fabric2612.platformPublishTasks.modrinth == ':publishModrinth2612Fabric'
        fabric2612.platformPublishTasks.curseforge == ':publishCurseforge2612Fabric'
        fabric2612.scenarioNodes == ['26.1.2-fabric']

        !forge2612.enabled
        forge2612.projectPath == ':forge:26.1.2'
        !forge2612.projectExists
        forge2612.platformPublishTasks.isEmpty()

        forge262.enabled
        forge262.projectPath == ':forge:26.2'
        forge262.platformPublishTasks.modrinth == ':publishModrinth262Forge'
        forge262.platformPublishTasks.curseforge == ':publishCurseforge262Forge'
        forge262.scenarioNodes == ['26.2-forge']
    }

    def "writeMultiloaderGraph writes the graph report"() {
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('writeMultiloaderGraph', '--stacktrace')
            .build()

        then:
        result.task(':writeMultiloaderGraph').outcome == TaskOutcome.SUCCESS
        def report = new File(testProjectDir, 'build/reports/multiloader/graph.json')
        report.isFile()
        new JsonSlurper().parse(report).mod.id == 'graphmod'
    }

    private void writeVersion(String version, String loaders) {
        def file = new File(testProjectDir, "versions/${version}/gradle.properties")
        file.parentFile.mkdirs()
        file.text = """
project.minecraft=${version}
project.version=9.9.9+${version}
project.java=25
project.build-java=25
project.enabled-loaders=${loaders}
mod.minecraft-range=>=${version}
mod.fabric-range=>=${version}
mod.forge-loader-range=[65,)
mod.neoforge-loader-range=[4,)
""".stripIndent()
    }

    private void createProject(String path, String archiveBaseName, String minecraftVersion, String javaVersion, boolean addRunClient) {
        def dir = new File(testProjectDir, path)
        dir.mkdirs()
        new File(dir, 'build.gradle').text = """
plugins {
    id 'java'
    id 'maven-publish'
}

group = 'com.example'
version = '9.9.9'
ext['project.minecraft'] = '${minecraftVersion}'
ext['project.java'] = '${javaVersion}'

tasks.named('jar') {
    archiveBaseName.set('${archiveBaseName}')
}

${addRunClient ? "tasks.register('runClient') {}" : ''}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from components.java
        }
    }
    repositories {
        maven {
            name = 'KafMaven'
            url = layout.buildDirectory.dir('repo')
        }
    }
}
""".stripIndent()
    }

    private static Map extractGraph(String output) {
        def start = output.indexOf('{\n')
        assert start >= 0
        new JsonSlurper().parseText(output.substring(start)) as Map
    }
}
