package com.iamkaf.multiloader.publishing

import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

class ReleaseManifestWriterTest extends Specification {

    @TempDir
    File tempDir

    def "writes the manifest contract"() {
        given:
        def manifest = manifest([file('mod-fabric-1.0+1.21.1.jar')])

        when:
        def json = new JsonSlurper().parseText(ReleaseManifestWriter.toJson(manifest))

        then:
        json.schema == 1
        json.mod == 'testmod'
        json.commit == 'a' * 40
        json.modrinthId == 'AbCd1234'
        json.curseforgeId == null
        json.releaseType == 'release'
        json.files.size() == 1
        def file = json.files[0]
        file.path == 'mod-fabric-1.0+1.21.1.jar'
        file.sha256 == 'b' * 64
        file.size == 12345
        file.minecraft == '1.21.1'
        file.version == '1.0+1.21.1'
        file.loaders == ['fabric']
        file.horizontal == false
        file.gameVersions == [modrinth: ['1.21.1'], curseforge: ['1.21.1']]
        file.java == 21
        file.environment == 'both'
        file.dependencies.modrinth == [[slug: 'amber', type: 'required']]
        file.dependencies.curseforge == [[slug: 'amber-lib', type: 'requiredDependency']]
    }

    def "rejects an unknown release type"() {
        when:
        ReleaseManifestWriter.toJson(manifest([file('a.jar')], 'stable'))

        then:
        thrown(IllegalStateException)
    }

    def "rejects an empty selection"() {
        when:
        ReleaseManifestWriter.toJson(manifest([]))

        then:
        thrown(IllegalStateException)
    }

    def "rejects two files with one name"() {
        when:
        ReleaseManifestWriter.toJson(manifest([file('a.jar'), file('a.jar')]))

        then:
        thrown(IllegalStateException)
    }

    def "hashes file contents"() {
        given:
        def jar = new File(tempDir, 'a.jar')
        jar.text = 'abc'

        expect:
        ReleaseManifestWriter.sha256(jar) == 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    }

    private static ReleaseManifest manifest(List<ManifestFile> files, String releaseType = 'release') {
        new ReleaseManifest('testmod', 'a' * 40, 'AbCd1234', null, releaseType, files)
    }

    private static ManifestFile file(String path) {
        new ManifestFile(
            path, 'b' * 64, 12345L, '1.21.1', '1.0+1.21.1', 'mod-fabric', ['fabric'], false,
            ['1.21.1'], ['1.21.1'], 21, 'both',
            [new ManifestDependency('amber', 'required')],
            [new ManifestDependency('amber-lib', 'requiredDependency')],
        )
    }
}
