package com.iamkaf.multiloader.core

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderCorePluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def "core plugin does not expose deprecated dynamic helper closures"() {
        given:
        new File(testProjectDir, 'settings.gradle.kts').text = 'rootProject.name = "core-test"\n'
        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.core")
}

tasks.register("printCoreHelpers") {
    doLast {
        val names = listOf(
            "requiredProp",
            "optionalProp",
            "catalogName",
            "catalogFor",
            "versionOrNull",
            "library",
            "useUnobfuscatedMinecraft",
            "sharedRepositories",
            "publishingRepositories",
            "mixinConfigs",
            "expandProps",
        )
        println(names.filter { extensions.extraProperties.has(it) }.joinToString(","))
    }
}
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('printCoreHelpers', '--stacktrace')
            .build()

        then:
        result.output.readLines().contains('')
    }

    def "core plugin rejects Groovy consumer scripts even without the settings plugin"() {
        given:
        new File(testProjectDir, 'settings.gradle.kts').text = 'rootProject.name = "core-test"\n'
        new File(testProjectDir, 'build.gradle').text = '''
plugins {
    id 'com.iamkaf.multiloader.core'
}
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('help', '--stacktrace')
            .buildAndFail()

        then:
        result.output.contains('Multiloader Conventions 3.0 requires Kotlin DSL build scripts')
        result.output.contains('build.gradle')
    }

    def "core plugin rejects Groovy settings scripts even without the settings plugin"() {
        given:
        new File(testProjectDir, 'settings.gradle').text = "rootProject.name = 'core-test'\n"
        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.core")
}
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('help', '--stacktrace')
            .buildAndFail()

        then:
        result.output.contains('Multiloader Conventions 3.0 requires Kotlin DSL build scripts')
        result.output.contains('settings.gradle')
    }
}
