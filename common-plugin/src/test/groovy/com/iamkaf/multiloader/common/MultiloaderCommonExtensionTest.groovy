package com.iamkaf.multiloader.common

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.Unroll

class MultiloaderCommonExtensionTest extends Specification {

    @Unroll
    def "resourcesFrom includes #path for #minecraftVersion when minimum is #minimumMinecraftVersion: #expected"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java')
        def extension = new MultiloaderCommonExtension(project, minecraftVersion, 'common')

        when:
        extension.resourcesFrom(path, { lane ->
            lane.minecraftAtLeast(minimumMinecraftVersion)
        } as Action<MultiloaderResourceLane>)

        then:
        sourceResourceDirs(project).contains(project.rootProject.file("common/${path}")) == expected

        where:
        minecraftVersion | minimumMinecraftVersion | expected
        '1.21.10'        | '1.21.10'               | true
        '26.2'           | '1.21.10'               | true
        '1.21.9'         | '1.21.10'               | false

        path = 'src/copper/generated'
    }

    def "resourcesFrom includes unconditional resource lanes"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java')
        def extension = new MultiloaderCommonExtension(project, '1.14.4', 'common')

        when:
        extension.resourcesFrom('src/always/generated')

        then:
        sourceResourceDirs(project).contains(project.rootProject.file('common/src/always/generated'))
    }

    def "resourcesFrom adds lane to Stonecutter staging when the stage task exists"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java')
        project.tasks.register('stageMergedResources', Sync) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            into(project.layout.buildDirectory.dir('merged'))
        }
        def laneDirectory = project.rootProject.file('common/src/copper/generated')
        laneDirectory.mkdirs()
        def marker = new File(laneDirectory, 'marker.json')
        marker.text = '{}'
        def extension = new MultiloaderCommonExtension(project, '26.2', 'common')

        when:
        extension.resourcesFrom('src/copper/generated', { lane ->
            lane.minecraftAtLeast('1.21.10')
        } as Action<MultiloaderResourceLane>)

        then:
        project.tasks.named('stageMergedResources', Sync).get().source.files.contains(marker)
    }

    private static Set<File> sourceResourceDirs(Project project) {
        def sourceSets = project.extensions.getByType(SourceSetContainer)
        sourceSets.getByName('main').resources.srcDirs
    }
}
