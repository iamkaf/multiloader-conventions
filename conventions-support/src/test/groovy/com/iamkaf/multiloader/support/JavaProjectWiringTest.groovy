package com.iamkaf.multiloader.support

import org.gradle.api.plugins.BasePluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class JavaProjectWiringTest extends Specification {

    @TempDir
    File projectDir

    def "shared Java wiring applies archive name toolchain archive defaults and manifest"() {
        given:
        def project = ProjectBuilder.builder()
            .withName('fabric')
            .withProjectDir(projectDir)
            .build()
        project.pluginManager.apply('java-library')
        def identity = new ProjectIdentity(
            'com.example',
            '1.2.3+26.2',
            'testmod',
            'Test Mod',
            'Kaf',
            '26.2',
            25,
            MultiloaderProjectRole.FABRIC,
        )
        identity.applyCoordinates(project)

        when:
        JavaProjectWiring.INSTANCE.configureArchiveName(project, identity.archiveName)
        JavaProjectWiring.INSTANCE.configureJavaBuild(project, identity.javaVersion, false)
        JavaProjectWiring.INSTANCE.configureArchiveAndResourceDefaults(project)
        JavaProjectWiring.INSTANCE.configureJarManifest(project, identity, identity.implementationTitle, ['testmod.mixins.json'])

        then:
        project.extensions.getByType(BasePluginExtension).archivesName.get() == 'testmod-fabric'
        project.tasks.named('sourcesJar', Jar).get().duplicatesStrategy.name() == 'EXCLUDE'
        project.tasks.named('jar', Jar).get().manifest.attributes['Built-On-Minecraft'] == '26.2'
        project.tasks.named('jar', Jar).get().manifest.attributes['MixinConfigs'] == 'testmod.mixins.json'
    }

    def "mod.artifact names archives when the mod id cannot hold the Maven name"() {
        given:
        def project = ProjectBuilder.builder()
            .withName('fabric')
            .withProjectDir(projectDir)
            .build()
        [
            'project.group'    : 'com.example',
            'project.version'  : '1.0.0+26.3',
            'project.minecraft': '26.3',
            'project.java'     : '25',
            'mod.id'           : 'test_mod',
            'mod.name'         : 'Test Mod',
            'mod.artifact'     : 'test-mod',
        ].each { name, value -> project.extensions.extraProperties.set(name, value) }

        when:
        def identity = ProjectIdentity.from(MultiloaderProjectContext.of(project), MultiloaderProjectRole.FABRIC)

        then:
        identity.archiveName == 'test-mod-fabric'
        identity.modId == 'test_mod'
    }
}
