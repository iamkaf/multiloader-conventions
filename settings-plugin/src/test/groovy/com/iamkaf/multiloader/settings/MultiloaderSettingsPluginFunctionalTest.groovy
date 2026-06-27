package com.iamkaf.multiloader.settings

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderSettingsPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def setup() {
        new File(testProjectDir, 'settings.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.settings")
}
'''.stripIndent()

        new File(testProjectDir, 'build.gradle.kts').text = '''
tasks.register("printProjectPaths") {
    doLast {
        println(rootProject.allprojects.map { it.path }.sorted().joinToString("\\n"))
    }
}
'''.stripIndent()

        new File(testProjectDir, 'gradle.properties').text = '''
project.plugins=3.0-SNAPSHOT
mod.name=Settings Test
'''.stripIndent()

        ['common', 'fabric', 'forge', 'neoforge'].each { branch ->
            def dir = new File(testProjectDir, branch)
            dir.mkdirs()
            new File(dir, 'build.gradle.kts').text = ''
        }

        writeVersion('26.1.2', 'fabric,neoforge')
        writeVersion('26.2', 'fabric,forge,neoforge')
    }

    def "no target properties includes the full Stonecutter graph"() {
        when:
        def result = runner('printProjectPaths').build()
        def paths = projectPaths(result.output)

        then:
        paths.containsAll([
            ':common:26.1.2',
            ':common:26.2',
            ':fabric:26.1.2',
            ':fabric:26.2',
            ':forge:26.2',
            ':neoforge:26.1.2',
            ':neoforge:26.2',
        ])
    }

    def "target versions and loaders limit included Stonecutter projects"() {
        when:
        def result = runner(
            'printProjectPaths',
            '-Pmultiloader.target.versions=26.2',
            '-Pmultiloader.target.loaders=forge',
        ).build()
        def paths = projectPaths(result.output)

        then:
        paths.contains(':common:26.2')
        paths.contains(':forge:26.2')
        !paths.contains(':fabric:26.2')
        !paths.contains(':neoforge:26.2')
        !paths.contains(':common:26.1.2')
        !paths.contains(':neoforge:26.1.2')
    }

    def "loader filter ignores loaders unavailable for the selected version"() {
        when:
        def result = runner(
            'printProjectPaths',
            '-Pmultiloader.target.versions=26.1.2',
            '-Pmultiloader.target.loaders=forge',
        ).build()
        def paths = projectPaths(result.output)

        then:
        paths.contains(':common:26.1.2')
        !paths.any { it.startsWith(':forge:') }
        !paths.any { it.startsWith(':fabric:') }
        !paths.any { it.startsWith(':neoforge:') }
    }

    def "unknown target versions fail in settings"() {
        when:
        def result = runner('projects', '-Pmultiloader.target.versions=26.3').buildAndFail()

        then:
        result.output.contains('Unknown multiloader.target.versions: 26.3')
    }

    def "unknown target loaders fail in settings"() {
        when:
        def result = runner('projects', '-Pmultiloader.target.loaders=quilt').buildAndFail()

        then:
        result.output.contains('Unknown multiloader.target.loaders: quilt')
    }

    def "Groovy build scripts are rejected for v3 consumers"() {
        given:
        new File(testProjectDir, 'fabric/build.gradle.kts').delete()
        new File(testProjectDir, 'fabric/build.gradle').text = ''

        when:
        def result = runner('projects').buildAndFail()

        then:
        result.output.contains('Multiloader Conventions 3.0 requires Kotlin DSL build scripts')
        result.output.contains('fabric/build.gradle')
    }

    def "root Groovy build script is rejected for v3 consumers"() {
        given:
        new File(testProjectDir, 'build.gradle').text = ''

        when:
        def result = runner('projects').buildAndFail()

        then:
        result.output.contains('Multiloader Conventions 3.0 requires Kotlin DSL build scripts')
        result.output.contains('build.gradle')
    }

    def "Groovy settings script is rejected for v3 consumers"() {
        given:
        new File(testProjectDir, 'settings.gradle.kts').delete()
        new File(testProjectDir, 'settings.gradle').text = '''
plugins {
    id 'com.iamkaf.multiloader.settings'
}
'''.stripIndent()

        when:
        def result = runner('projects').buildAndFail()

        then:
        result.output.contains('Multiloader Conventions 3.0 requires Kotlin DSL build scripts')
        result.output.contains('settings.gradle')
    }

    def "auxiliary Groovy Gradle scripts are rejected for v3 consumers"() {
        given:
        def script = new File(testProjectDir, 'gradle/legacy-loader-glue.gradle')
        script.parentFile.mkdirs()
        script.text = ''

        when:
        def result = runner('projects').buildAndFail()

        then:
        result.output.contains('Multiloader Conventions 3.0 requires Kotlin DSL build scripts')
        result.output.contains('gradle/legacy-loader-glue.gradle')
    }

    private void writeVersion(String version, String loaders) {
        def file = new File(testProjectDir, "versions/${version}/gradle.properties")
        file.parentFile.mkdirs()
        file.text = """
project.minecraft=${version}
project.version=9.9.9+${version}
project.java=25
project.enabled-loaders=${loaders}
""".stripIndent()
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments((args as List<String>) + ['--stacktrace'])
    }

    private static Set<String> projectPaths(String output) {
        output.readLines()
            .findAll { it.startsWith(':') }
            .toSet()
    }
}
