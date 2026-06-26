package com.iamkaf.multiloader.support

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class ProjectIdentityTest extends Specification {

    def "identity captures mod coordinates and role archive naming"() {
        given:
        def project = ProjectBuilder.builder().withName('26.2').build()
        [
            'project.group'    : 'com.example',
            'project.version'  : '1.2.3+26.2',
            'project.minecraft': '26.2',
            'project.java'     : '25',
            'mod.id'           : 'testmod',
            'mod.name'         : 'Test Mod',
            'mod.authors'      : 'Kaf',
        ].each { key, value -> project.extensions.extraProperties.set(key, value) }

        when:
        def identity = ProjectIdentity.from(MultiloaderProjectContext.of(project), MultiloaderProjectRole.FORGE)

        then:
        identity.archiveName == 'testmod-forge'
        identity.loader == 'forge'
        identity.minecraftVersion == '26.2'
        identity.javaVersion == 25

        when:
        identity.applyCoordinates(project)

        then:
        project.group == 'com.example'
        project.version == '1.2.3+26.2'
    }
}
