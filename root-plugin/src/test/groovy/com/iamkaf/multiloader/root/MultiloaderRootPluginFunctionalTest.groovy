package com.iamkaf.multiloader.root

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderRootPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def setup() {
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "root-test"
include("common:26.1.2", "common:26.2")
include("fabric:26.1.2", "fabric:26.2")
include("forge:26.2")
include("neoforge:26.1.2", "neoforge:26.2")
'''.stripIndent()

        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.root")
}

group = "com.example"
version = "9.9.9"
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
project.plugins=3.0-SNAPSHOT
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
        graph.conventions.version == '3.0-SNAPSHOT'

        version2612.enabledLoaders == ['fabric', 'neoforge']
        version2612.horizontal == [
            enabled: false,
            planned: false,
            stabilityTier: null,
            unsafeAcknowledged: false,
            selectedLoaders: [],
            mergeTask: null,
            validateTask: null,
            artifactPath: null,
            publishable: false,
            nonPublishableReason: null,
            platformPublishTasks: [:],
        ]
        version2612.common.projectPath == ':common:26.1.2'
        version2612.common.buildTask == ':common:26.1.2:build'

        fabric2612.enabled
        fabric2612.projectPath == ':fabric:26.1.2'
        fabric2612.buildTask == ':fabric:26.1.2:build'
        fabric2612.runClientTask == ':fabric:26.1.2:runClient'
        fabric2612.artifactTask == ':fabric:26.1.2:jar'
        fabric2612.artifactPath.endsWith('fabric/26.1.2/build/libs/graphmod-fabric-9.9.9+26.1.2.jar')
        fabric2612.mavenPublishTasks == [
            ':fabric:26.1.2:publishMavenJavaPublicationToKafMavenRepository',
            ':fabric:26.1.2:publishMavenJavaPublicationToMavenLocal',
        ]
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

    def "horizontal merge is opt-in and uses loader archive providers from root tasks"() {
        given:
        new File(testProjectDir, 'build.gradle.kts') << '''

multiloaderArtifacts {
    horizontalMerge {
        enabled.set(true)
        version("26.2")
    }
}

tasks.register("verifyHorizontalWiring") {
    doLast {
        val merge = tasks.named<com.iamkaf.multiloader.root.ForgixHorizontalMergeTask>("mergeHorizontalJar262").get()
        check(merge.fabricJar.get().asFile.name == "fabric-provider.jar")
        check(merge.forgeJar.get().asFile.name == "forge-provider.jar")
        check(merge.neoForgeJar.get().asFile.name == "neoforge-provider.jar")
    }
}
'''.stripIndent()
        configureArchiveProvider('fabric/26.2', 'fabric-provider.jar')
        configureArchiveProvider('forge/26.2', 'forge-provider.jar')
        configureArchiveProvider('neoforge/26.2', 'neoforge-provider.jar')
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('verifyHorizontalWiring', 'printMultiloaderGraph', '--stacktrace')
            .build()
        def graph = extractGraph(result.output)
        def horizontal = graph.versions.find { it.name == '26.2' }.horizontal

        then:
        result.task(':verifyHorizontalWiring').outcome == TaskOutcome.SUCCESS
        horizontal.enabled
        horizontal.planned
        horizontal.stabilityTier == 'stable'
        !horizontal.unsafeAcknowledged
        horizontal.selectedLoaders == ['fabric', 'forge', 'neoforge']
        horizontal.mergeTask == ':mergeHorizontalJar262'
        horizontal.validateTask == ':validateHorizontalJar262'
        horizontal.artifactPath == 'build/libs/horizontal/26.2/graphmod-9.9.9+26.2.jar'
        !horizontal.publishable
        horizontal.platformPublishTasks == [:]
        horizontal.nonPublishableReason.contains('dependency semantics')
        !graph.versions.find { it.name == '26.1.2' }.horizontal.enabled

        and: 'existing raw publication tasks remain present'
        graph.versions.find { it.name == '26.2' }.loaders.every { loader ->
            !loader.enabled || loader.platformPublishTasks.keySet() == ['modrinth', 'curseforge'] as Set
        }
    }

    def "horizontal merge tasks do not exist by default"() {
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('tasks', '--all', '--stacktrace')
            .build()

        then:
        !result.output.contains('mergeHorizontalJar')
        !result.output.contains('validateHorizontalJar')
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

    def "writeMultiloaderGraph does not realize unrelated lazy task providers"() {
        given:
        new File(testProjectDir, 'common/26.1.2/build.gradle.kts') << '''

tasks.register("genSourcesWithCfr") {
    throw org.gradle.api.GradleException("genSourcesWithCfr should not be realized while writing the graph")
}
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('writeMultiloaderGraph', '--stacktrace')
            .build()

        then:
        result.task(':writeMultiloaderGraph').outcome == TaskOutcome.SUCCESS
    }

    def "target scope limits root publish task registration"() {
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments(
                'tasks',
                '--all',
                '-Pmultiloader.target.versions=26.2',
                '-Pmultiloader.target.loaders=forge',
                '--stacktrace',
            )
            .build()

        then:
        result.output.contains('publishModrinth262Forge')
        result.output.contains('publishCurseforge262Forge')
        !result.output.contains('publishModrinth262Fabric')
        !result.output.contains('publishCurseforge262Fabric')
        !result.output.contains('publishModrinth2612')
        !result.output.contains('publishCurseforge2612')
    }

    def "root plugin exposes multiloader stonecutter extension"() {
        given:
        Project project = ProjectBuilder.builder().build()

        when:
        project.pluginManager.apply(MultiloaderRootPlugin)

        then:
        project.extensions.findByName('multiloaderStonecutter') instanceof MultiloaderStonecutterExtension
    }

    def "multiloader stonecutter active version honors explicit and scoped overrides"() {
        given:
        Project project = ProjectBuilder.builder().build()
        def extension = new MultiloaderStonecutterExtension(project)

        expect:
        extension.active('26.2') == '26.2'

        when:
        project.extensions.extraProperties.set('multiloader.target.versions', '26.1.2,26.1')

        then:
        extension.active('26.2') == '26.1.2'

        when:
        project.extensions.extraProperties.set('multiloader.stonecutter.active', '1.18.2')

        then:
        extension.active('26.2') == '1.18.2'
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
        new File(dir, 'build.gradle.kts').text = """
plugins {
    java
    `maven-publish`
}

group = "com.example"
version = "9.9.9"
extra["project.minecraft"] = "${minecraftVersion}"
extra["project.java"] = "${javaVersion}"

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveBaseName.set("${archiveBaseName}")
}

${addRunClient ? 'tasks.register("runClient") {}' : ''}

publishing {
    publications {
        create<org.gradle.api.publish.maven.MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "KafMaven"
            url = layout.buildDirectory.dir("repo").get().asFile.toURI()
        }
    }
}
""".stripIndent()
    }

    private void configureArchiveProvider(String path, String archiveFileName) {
        new File(testProjectDir, "$path/build.gradle.kts") << """

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    destinationDirectory.set(layout.buildDirectory.dir("provider-output"))
    this.archiveFileName.set("${archiveFileName}")
}
""".stripIndent()
    }

    private static Map extractGraph(String output) {
        def start = output.indexOf('{\n')
        assert start >= 0
        new JsonSlurper().parseText(output.substring(start)) as Map
    }
}
