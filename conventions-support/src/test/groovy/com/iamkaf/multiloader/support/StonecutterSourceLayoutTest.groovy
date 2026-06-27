package com.iamkaf.multiloader.support

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class StonecutterSourceLayoutTest extends Specification {

    @TempDir
    File testProjectDir

    def "common layout without Stonecutter keeps generated resources as a normal resource root"() {
        given:
        def project = ProjectBuilder.builder()
            .withName('common')
            .withProjectDir(new File(testProjectDir, 'common'))
            .build()
        project.pluginManager.apply('java-library')

        when:
        StonecutterSourceLayout.configureCommon(project, '26.2', false)

        then:
        def main = project.extensions.getByType(SourceSetContainer).getByName('main')
        main.resources.srcDirs.any { it.path.endsWith('src/main/generated') }
        project.tasks.findByName(StonecutterSourceLayout.STAGE_JAVA_TASK) == null
        project.tasks.findByName(StonecutterSourceLayout.STAGE_RESOURCES_TASK) == null
    }

    def "common layout with Stonecutter owns merged Java and resource staging"() {
        given:
        def project = ProjectBuilder.builder()
            .withName('common')
            .withProjectDir(new File(testProjectDir, 'common'))
            .build()
        project.pluginManager.apply('java-library')
        project.tasks.register('stonecutterGenerate')

        when:
        StonecutterSourceLayout.configureCommon(project, '26.2', true)

        then:
        project.tasks.findByName(StonecutterSourceLayout.STAGE_JAVA_TASK) != null
        project.tasks.findByName(StonecutterSourceLayout.STAGE_RESOURCES_TASK) != null

        and:
        def main = project.extensions.getByType(SourceSetContainer).getByName('main')
        main.java.srcDirs == [project.layout.buildDirectory.dir('generated/merged/main/java').get().asFile] as Set
        main.resources.srcDirs == [project.layout.buildDirectory.dir('generated/merged/main/resources').get().asFile] as Set
    }

    @Unroll
    def "#loader layout stages common, generated, and version overlay roots"() {
        given:
        def root = ProjectBuilder.builder()
            .withName('root')
            .withProjectDir(testProjectDir)
            .build()
        def common = ProjectBuilder.builder()
            .withName('common')
            .withParent(root)
            .withProjectDir(new File(testProjectDir, 'common'))
            .build()
        def project = ProjectBuilder.builder()
            .withName(loader)
            .withParent(root)
            .withProjectDir(new File(testProjectDir, loader))
            .build()
        common.pluginManager.apply('java-library')
        project.pluginManager.apply('java-library')
        project.extensions.getByType(JavaPluginExtension).withSourcesJar()
        project.extensions.getByType(JavaPluginExtension).withJavadocJar()
        common.tasks.register('stonecutterGenerate')
        project.tasks.register('stonecutterGenerate')

        when:
        StonecutterSourceLayout.configureCommon(common, '26.2', true)
        StonecutterSourceLayout.configureLoader(project, loader, '26.2', common)
        def commonResource = new File(
            common.layout.buildDirectory.dir('generated/merged/main/resources').get().asFile,
            'common-marker.txt'
        )
        commonResource.parentFile.mkdirs()
        commonResource.text = 'common'

        then:
        project.tasks.findByName(StonecutterSourceLayout.STAGE_JAVA_TASK) != null
        project.tasks.findByName(StonecutterSourceLayout.STAGE_RESOURCES_TASK) != null

        and:
        def main = project.extensions.getByType(SourceSetContainer).getByName('main')
        main.java.srcDirs == [project.layout.buildDirectory.dir('generated/merged/main/java').get().asFile] as Set
        main.resources.srcDirs == [project.layout.buildDirectory.dir('generated/merged/main/resources').get().asFile] as Set
        project.tasks.named(StonecutterSourceLayout.STAGE_RESOURCES_TASK, Sync).get().source.files.contains(commonResource)

        where:
        loader << ['fabric', 'forge', 'neoforge']
    }

    def "Forge graph-only layout registers empty staging roots without Stonecutter dependencies"() {
        given:
        def project = ProjectBuilder.builder()
            .withName('forge')
            .withProjectDir(new File(testProjectDir, 'forge'))
            .build()
        project.pluginManager.apply('java-library')

        when:
        StonecutterSourceLayout.configureGraphOnly(project)

        then:
        project.tasks.findByName(StonecutterSourceLayout.STAGE_JAVA_TASK) != null
        project.tasks.findByName(StonecutterSourceLayout.STAGE_RESOURCES_TASK) != null

        and:
        def main = project.extensions.getByType(SourceSetContainer).getByName('main')
        main.java.srcDirs == [project.layout.buildDirectory.dir('generated/merged/main/java').get().asFile] as Set
        main.resources.srcDirs == [project.layout.buildDirectory.dir('generated/merged/main/resources').get().asFile] as Set
    }
}
