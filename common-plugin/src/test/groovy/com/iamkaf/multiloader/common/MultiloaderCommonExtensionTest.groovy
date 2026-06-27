package com.iamkaf.multiloader.common

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.Unroll

class MultiloaderCommonExtensionTest extends Specification {

    @Unroll
    def "resourcesFrom includes #path for #minecraftVersion when minimum is #minimumMinecraftVersion: #expected"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java')
        def extension = new MultiloaderCommonExtension(project, minecraftVersion)

        when:
        extension.resourcesFrom(path, { lane ->
            lane.minecraftAtLeast(minimumMinecraftVersion)
        } as Action<MultiloaderResourceLane>)

        then:
        sourceResourceDirs(project).contains(project.file(path)) == expected

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
        def extension = new MultiloaderCommonExtension(project, '1.14.4')

        when:
        extension.resourcesFrom('src/always/generated')

        then:
        sourceResourceDirs(project).contains(project.file('src/always/generated'))
    }

    private static Set<File> sourceResourceDirs(Project project) {
        def sourceSets = project.extensions.getByType(SourceSetContainer)
        sourceSets.getByName('main').resources.srcDirs
    }
}
