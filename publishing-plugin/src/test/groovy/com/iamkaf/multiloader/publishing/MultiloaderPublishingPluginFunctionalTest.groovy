package com.iamkaf.multiloader.publishing

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class MultiloaderPublishingPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    def setup() {
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "publishing-test"
include("fabric", "forge", "neoforge")
'''.stripIndent()

        new File(testProjectDir, 'gradle.properties').text = '''
project.minecraft=1.21.11
project.java=21
mod.name=Test Mod
mod.id=testmod
publish.game-versions=1.21.11
publish.release-type=beta
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.publishing")
}

group = "com.example"
version = "1.2.3"
'''.stripIndent()

        createLoaderProject('fabric', '{"schemaVersion":1,"id":"testmod","version":"1.2.3"}', 'fabric.mod.json')
        createLoaderProject('forge', '[mods]\\n[[mods]]\\nmodId=\"testmod\"\\n', 'META-INF/mods.toml')
        createLoaderProject('neoforge', 'modLoader="javafml"\\n[[mods]]\\nmodId="testmod"\\n', 'META-INF/neoforge.mods.toml')
    }

    def "writeReleaseManifest stages one jar per loader"() {
        when:
        def result = run('writeReleaseManifest')
        def json = manifest()

        then:
        result.output.contains('[Publishing] Copied :fabric ->')
        result.output.contains('[Publishing] Copied :forge ->')
        result.output.contains('[Publishing] Copied :neoforge ->')
        json.releaseType == 'beta'
        json.curseforgeId == '123456'
        json.files.collect { [it.path, it.displayName, it.loaders] } == [
            ['testmod-fabric-1.2.3.jar', 'testmod-fabric-1.2.3', ['fabric']],
            ['testmod-forge-1.2.3.jar', 'testmod-forge-1.2.3', ['forge']],
            ['testmod-neoforge-1.2.3.jar', 'testmod-neoforge-1.2.3', ['neoforge']],
        ]
        json.files.every { it.gameVersions == [modrinth: ['1.21.11'], curseforge: ['1.21.11']] && it.java == 21 }
    }

    def "a loader's assemble task stages only its own jar"() {
        when:
        def result = run('publishingAssembleFabric')

        then:
        result.task(':publishingAssembleFabric').outcome == TaskOutcome.SUCCESS
        result.output.contains('[Publishing] Copied :fabric ->')
        !result.output.contains('[Publishing] Copied :forge ->')
        !result.output.contains('[Publishing] Copied :neoforge ->')
    }

    def "writeReleaseManifest skips disabled loaders from project.enabled-loaders"() {
        given:
        new File(testProjectDir, 'gradle.properties').text = '''
project.minecraft=26.1.2
project.java=25
project.enabled-loaders=fabric,neoforge
mod.name=Test Mod
mod.id=testmod
publish.game-versions=26.1.2
publish.release-type=release
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        when:
        run('writeReleaseManifest')

        then:
        manifest().files*.path == ['testmod-fabric-1.2.3.jar', 'testmod-neoforge-1.2.3.jar']
    }

    def "writeReleaseManifest describes the staged jars with per-line dependencies"() {
        given:
        new File(testProjectDir, 'gradle.properties').text = '''
mod.name=Test Mod
mod.id=testmod
publish.release-type=release
publish.modrinth.id=test-mod
environments.client=required
environments.server=required
'''.stripIndent()
        def versionProperties = new File(testProjectDir, 'versions/26.2/gradle.properties')
        versionProperties.parentFile.mkdirs()
        versionProperties.text = '''
project.minecraft=26.2
project.java=25
project.enabled-loaders=fabric,neoforge
publish.game-versions=26.2
dependencies.modrinth.required=amber
dependencies.curseforge.required=amber-lib
'''.stripIndent()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('writeReleaseManifest', '-Pmultiloader.stonecutter.active=26.2', '-Ppublish.commit=' + ('c' * 40), '--stacktrace')
            .build()
        def json = new groovy.json.JsonSlurper().parse(new File(testProjectDir, 'build/release/release-manifest.json'))

        then:
        result.task(':writeReleaseManifest').outcome == TaskOutcome.SUCCESS
        json.commit == 'c' * 40
        json.modrinthId == 'test-mod'
        json.curseforgeId == null
        json.files*.path.sort() == ['testmod-fabric-1.2.3.jar', 'testmod-neoforge-1.2.3.jar']
        json.files.every { it.minecraft == '26.2' && it.java == 25 && it.environment == 'both' && it.sha256.size() == 64 }
        json.files[0].dependencies.modrinth == [[slug: 'amber', type: 'required']]
        json.files[0].dependencies.curseforge == [[slug: 'amber-lib', type: 'requiredDependency']]
    }

    def "explicit publications support matrix artifacts with per-project game and java versions"() {
        given:
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "publishing-matrix-test"
include("fabric:1.21.11", "neoforge:26.1.2")
'''.stripIndent()

        new File(testProjectDir, 'build.gradle.kts').text = '''
plugins {
    id("com.iamkaf.multiloader.publishing")
}

group = "com.example"
version = "1.2.3"

multiloaderPublishing {
    publication("fabric-1.21.11") {
        project(":fabric:1.21.11")
        loader("fabric")
    }
    publication("neoforge-26.1.2") {
        project(":neoforge:26.1.2")
        loader("neoforge")
    }
}
'''.stripIndent()

        new File(testProjectDir, 'gradle.properties').text = '''
mod.name=Test Mod
mod.id=testmod
publish.release-type=beta
publish.modrinth.id=test-mod
publish.curseforge.id=123456
environments.client=required
environments.server=required
'''.stripIndent()

        createNestedLoaderProject('fabric/1.21.11', 'testmod-fabric', '{"schemaVersion":1,"id":"testmod","version":"1.2.3"}', 'fabric.mod.json', '1.21.11', '21')
        createNestedLoaderProject('neoforge/26.1.2', 'testmod-neoforge', 'modLoader="javafml"\\n[[mods]]\\nmodId="testmod"\\n', 'META-INF/neoforge.mods.toml', '26.1.2', '25')

        when:
        run('writeReleaseManifest')

        then:
        manifest().files.collect { [it.path, it.minecraft, it.java, it.loaders] } == [
            ['testmod-fabric-1.2.3.jar', '1.21.11', 21, ['fabric']],
            ['testmod-neoforge-1.2.3.jar', '26.1.2', 25, ['neoforge']],
        ]
    }

    private run(String task) {
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments(task, '-Ppublish.commit=' + ('c' * 40), '--stacktrace')
            .build()
    }

    private manifest() {
        new groovy.json.JsonSlurper().parse(new File(testProjectDir, 'build/release/release-manifest.json'))
    }

    private void createLoaderProject(String name, String metadataContents, String metadataPath) {
        def dir = new File(testProjectDir, name)
        dir.mkdirs()
        new File(dir, 'build.gradle.kts').text = """
plugins {
    java
}

group = "com.example"
version = "1.2.3"

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveBaseName.set("testmod-${name}")
}
""".stripIndent()

        def metadata = new File(dir, "src/main/resources/${metadataPath}")
        metadata.parentFile.mkdirs()
        metadata.text = metadataContents
        createModClass(dir)
    }

    private void createNestedLoaderProject(String path, String archiveBaseName, String metadataContents, String metadataPath, String minecraftVersion, String javaVersion) {
        def dir = new File(testProjectDir, path)
        dir.mkdirs()
        new File(dir, 'build.gradle.kts').text = """
plugins {
    java
}

group = "com.example"
version = "1.2.3"
extra["project.minecraft"] = "${minecraftVersion}"
extra["project.java"] = "${javaVersion}"

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveBaseName.set("${archiveBaseName}")
}
""".stripIndent()

        def metadata = new File(dir, "src/main/resources/${metadataPath}")
        metadata.parentFile.mkdirs()
        metadata.text = metadataContents
        createModClass(dir)
    }

    private static void createModClass(File dir) {
        def source = new File(dir, 'src/main/java/com/example/TestMod.java')
        source.parentFile.mkdirs()
        source.text = 'package com.example;\n\npublic final class TestMod {}\n'
    }
}
