package com.iamkaf.multiloader.root

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class TeaKitNodeReaderTest extends Specification {

    @TempDir
    File projectDir

    def "reads TeaKit node loader and Minecraft metadata without parsing unrelated sections"() {
        given:
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        new File(projectDir, 'teakit.toml').text = '''
[nodes."26.2-fabric"]
loader = "fabric"
minecraft = "26.2"

[nodes."26.2-forge"]
loader = "forge"
minecraft = "26.2"

[other]
loader = "ignored"
'''.stripIndent()

        when:
        def nodes = TeaKitNodeReader.INSTANCE.read(project)

        then:
        nodes*.name == ['26.2-fabric', '26.2-forge']
        nodes*.loader == ['fabric', 'forge']
        nodes*.minecraft == ['26.2', '26.2']
    }
}
