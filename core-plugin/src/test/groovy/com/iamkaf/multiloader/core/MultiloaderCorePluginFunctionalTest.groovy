package com.iamkaf.multiloader.core

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderCorePluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def "core plugin does not expose deprecated dynamic helper closures"() {
        given:
        new File(testProjectDir, 'settings.gradle').text = "rootProject.name = 'core-test'\n"
        new File(testProjectDir, 'build.gradle').text = '''
plugins {
    id 'com.iamkaf.multiloader.core'
}

tasks.register('printCoreHelpers') {
    doLast {
        def names = [
            'requiredProp',
            'optionalProp',
            'catalogName',
            'catalogFor',
            'versionOrNull',
            'library',
            'useUnobfuscatedMinecraft',
            'sharedRepositories',
            'publishingRepositories',
            'mixinConfigs',
            'expandProps',
        ]
        println(names.findAll { extensions.extraProperties.has(it) }.join(','))
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
}
