package com.iamkaf.multiloader.support

import spock.lang.Specification
import spock.lang.TempDir

class VersionMetadataFilesTest extends Specification {

    @TempDir
    File tempDir

    def "fallback metadata includes full policy not just enabled loaders"() {
        given:
        def versionDir = new File(tempDir, '26.2')
        versionDir.mkdirs()

        when:
        def props = VersionMetadataFiles.INSTANCE.versionMetadata(versionDir)

        then:
        props.getProperty('project.minecraft') == '26.2'
        props.getProperty('project.java') == '25'
        props.getProperty('project.build-java') == '25'
        props.getProperty('project.enabled-loaders') == 'fabric,forge,neoforge'
        props.getProperty('project.catalog-name') == 'libsMc262'
        props.getProperty('project.catalog-coordinate') == 'com.iamkaf.platform:mc-26.2:26.2-SNAPSHOT'
        props.getProperty('mod.forge-loader-range') == '[65,)'
    }

    def "materialization replaces managed keys and preserves project-specific keys"() {
        given:
        def versionDir = new File(tempDir, '1.14.4')
        versionDir.mkdirs()
        new File(versionDir, 'gradle.properties').text = '''\
project.minecraft=1.14.4
project.java=8
project.build-java=17
dependencies.modrinth.required=amber
custom.side-lane=keep-me
'''.stripIndent()

        when:
        def differences = VersionMetadataFiles.INSTANCE.differences(versionDir)
        VersionMetadataFiles.INSTANCE.writeMaterializedMetadata(versionDir)
        def materialized = new Properties()
        new File(versionDir, 'gradle.properties').withInputStream { materialized.load(it) }

        then:
        differences*.key.contains('project.java')
        differences*.key.contains('project.build-java')
        materialized.getProperty('project.java') == '16'
        materialized.getProperty('project.build-java') == '21'
        materialized.getProperty('project.enabled-loaders') == 'fabric'
        materialized.getProperty('dependencies.modrinth.required') == 'amber'
        materialized.getProperty('custom.side-lane') == 'keep-me'
    }
}
