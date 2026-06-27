package com.iamkaf.multiloader.support

import spock.lang.Specification
import spock.lang.Unroll

class VersionPolicyTest extends Specification {

    @Unroll
    def "metadata is available for supported release #version"() {
        expect:
        def metadata = VersionPolicy.INSTANCE.metadata(version)
        metadata.minecraftVersion == version
        metadata.catalogName == VersionPolicy.INSTANCE.catalogName(version)
        metadata.catalogCoordinate == "com.iamkaf.platform:mc-${version}:${version}-SNAPSHOT"
        !metadata.enabledLoaders.isEmpty()
        metadata.javaVersion > 0
        metadata.buildJavaVersion > 0

        where:
        version << VersionPolicy.INSTANCE.supportedReleaseVersions
    }

    def "26.2 policy captures the modern all-loader path"() {
        when:
        def metadata = VersionPolicy.INSTANCE.metadata('26.2')

        then:
        metadata.javaVersion == 25
        metadata.buildJavaVersion == 25
        metadata.enabledLoaders*.id == ['fabric', 'forge', 'neoforge']
        metadata.catalogName == 'libsMc262'
        metadata.catalogCoordinate == 'com.iamkaf.platform:mc-26.2:26.2-SNAPSHOT'
        metadata.forgeLoaderRange == '[65,)'
        metadata.fabricDependencyStrategy == FabricDependencyStrategy.MODERN_UNOBFUSCATED
        VersionPolicy.INSTANCE.fabricPublicationArtifact('26.2') == PublicationArtifactStrategy.JAR
    }

    @Unroll
    def "#version keeps the 26.1 Forge loader range"() {
        expect:
        VersionPolicy.INSTANCE.metadata(version).forgeLoaderRange == '[62,)'

        where:
        version << ['26.1', '26.1.1', '26.1.2']
    }

    @Unroll
    def "legacy Fabric-only line #version does not enable Forge or NeoForge"() {
        expect:
        VersionPolicy.INSTANCE.metadata(version).enabledLoaders*.id == ['fabric']

        where:
        version << ['1.14.4', '1.15', '1.15.2', '1.16', '1.16.5', '1.17', '1.20.5']
    }

    def "1.21.2 intentionally excludes Forge but keeps NeoForge"() {
        expect:
        VersionPolicy.INSTANCE.metadata('1.21.2').enabledLoaders*.id == ['fabric', 'neoforge']
    }

    def "1.20.6 Forge uses a Mixin compatibility level supported by its bundled Mixin"() {
        when:
        def metadata = VersionPolicy.INSTANCE.metadata('1.20.6')

        then:
        metadata.javaVersion == 21
        metadata.mixinCompatCommon == 'JAVA_21'
        metadata.mixinCompatFabric == 'JAVA_21'
        metadata.mixinCompatForge == 'JAVA_17'
    }

    @Unroll
    def "#version keeps old mod bytecode separate from the modern build runtime"() {
        when:
        def metadata = VersionPolicy.INSTANCE.metadata(version)

        then:
        metadata.javaVersion == javaVersion
        metadata.buildJavaVersion == 21

        where:
        version  | javaVersion
        '1.14.4' | 16
        '1.16.5' | 16
        '1.17.1' | 16
        '1.20.1' | 17
        '1.21.1' | 21
    }

    @Unroll
    def "old Fabric lines use split API module policy for #version"() {
        expect:
        VersionPolicy.INSTANCE.usesLegacyFabricApiModules(version)

        where:
        version << ['1.16', '1.16.1']
    }

    @Unroll
    def "non-26 Fabric publication uses remapJar for #version"() {
        expect:
        VersionPolicy.INSTANCE.fabricPublicationArtifact(version) == PublicationArtifactStrategy.FABRIC_REMAP_JAR

        where:
        version << ['1.18.2', '1.21.11']
    }

    @Unroll
    def "#version maps resource pack expansion defaults"() {
        expect:
        VersionPolicy.INSTANCE.resourcePackFormat(version) == format
        VersionPolicy.INSTANCE.resourcePackMinMaxSnippet(version) == minMax

        where:
        version   | format | minMax
        '1.18.2'  | '8'    | ''
        '1.20.4'  | '15'   | ''
        '1.21.11' | '81'   | ',\n    "min_format": 81,\n    "max_format": 81'
        '26.2'    | '8'    | ''
    }
}
