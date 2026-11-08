package com.iamkaf.multiloader.support

import org.gradle.api.publish.PublishingExtension
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class GraphOnlyBuildTest extends Specification {

    def "graph-only nodes take loader dependency lanes from build scripts and keep their publication"() {
        given:
        def project = ProjectBuilder.builder().withName('1.21.1').build()
        [
            'project.group'    : 'com.example',
            'project.version'  : '1.2.3+1.21.1',
            'project.minecraft': '1.21.1',
            'project.java'     : '21',
            'mod.id'           : 'testmod',
            'mod.name'         : 'Test Mod',
        ].each { key, value -> project.extensions.extraProperties.set(key, value) }
        project.pluginManager.apply('java-library')
        project.pluginManager.apply('maven-publish')

        when:
        GraphOnlyBuild.configureNode(project, MultiloaderProjectContext.of(project), MultiloaderProjectRole.FABRIC, ['runClient'])
        project.dependencies.add('modImplementation', 'com.example:lib:1.0')
        project.dependencies.add('modLocalRuntime', 'com.example:runtime:1.0')

        then:
        project.tasks.findByName('runClient') != null
        project.extensions.getByType(PublishingExtension).publications.findByName('mavenJava').artifactId == 'testmod-fabric'
        project.version == '1.2.3+1.21.1'
    }
}
