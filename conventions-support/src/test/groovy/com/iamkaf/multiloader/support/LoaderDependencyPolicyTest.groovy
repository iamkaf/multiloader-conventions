package com.iamkaf.multiloader.support

import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import java.util.Optional

class LoaderDependencyPolicyTest extends Specification {

    @TempDir
    File projectDir

    def "1.16.5 Forge dependency resolution accepts Java 16 helper mod variants"() {
        given:
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        project.pluginManager.apply('java-library')

        when:
        LoaderDependencyPolicy.INSTANCE.configureLegacyForgeDependencyVariantCompatibility(project, '1.16.5')

        then:
        project.configurations.compileClasspath.attributes
            .getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE) == 16
        project.configurations.runtimeClasspath.attributes
            .getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE) == 16
    }

    def "modern Forge dependency resolution keeps default JVM variant selection"() {
        given:
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        project.pluginManager.apply('java-library')
        def compileJvm = project.configurations.compileClasspath.attributes
            .getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE)
        def runtimeJvm = project.configurations.runtimeClasspath.attributes
            .getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE)

        when:
        LoaderDependencyPolicy.INSTANCE.configureLegacyForgeDependencyVariantCompatibility(project, '26.2')

        then:
        project.configurations.compileClasspath.attributes
            .getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE) == compileJvm
        project.configurations.runtimeClasspath.attributes
            .getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE) == runtimeJvm
    }

    def "modern Fabric adds C2ME to the ordinary runtime classpath when catalogued"() {
        given:
        def project = javaProject()
        def catalog = c2meCatalog(project, 'c2me-fabric', '0.4.2-alpha.0.12+26.2')

        when:
        LoaderDependencyPolicy.INSTANCE.addC2meRuntime(
            project,
            MultiloaderProjectContext.of(project),
            catalog,
            identity(MultiloaderProjectRole.FABRIC),
            LoaderId.FABRIC,
            '26.2',
        )

        then:
        project.configurations.runtimeOnly.allDependencies.any {
            it.group == 'example' && it.name == 'c2me-fabric'
        }
    }

    def "legacy Fabric adds C2ME to the Loom local-runtime classpath when catalogued"() {
        given:
        def project = javaProject()
        project.configurations.maybeCreate('modLocalRuntime')
        def catalog = c2meCatalog(project, 'c2me-fabric', '0.2.0+alpha.11.109+1.21')

        when:
        LoaderDependencyPolicy.INSTANCE.addC2meRuntime(
            project,
            MultiloaderProjectContext.of(project),
            catalog,
            identity(MultiloaderProjectRole.FABRIC),
            LoaderId.FABRIC,
            '1.21',
        )

        then:
        project.configurations.modLocalRuntime.allDependencies.any {
            it.group == 'example' && it.name == 'c2me-fabric'
        }
    }

    def "NeoForge adds C2ME to the runtime classpath when catalogued"() {
        given:
        def project = javaProject()
        def catalog = c2meCatalog(project, 'c2me-neoforge', '0.4.2-alpha.0.90+26.2')

        when:
        LoaderDependencyPolicy.INSTANCE.addC2meRuntime(
            project,
            MultiloaderProjectContext.of(project),
            catalog,
            identity(MultiloaderProjectRole.NEOFORGE),
            LoaderId.NEOFORGE,
            '26.2',
        )

        then:
        project.configurations.runtimeOnly.allDependencies.any {
            it.group == 'example' && it.name == 'c2me-neoforge'
        }
    }

    def "C2ME does not resolve a catalogued null version"() {
        given:
        def project = javaProject()
        def catalog = Mock(VersionCatalog)
        catalog.findVersion('c2me-neoforge') >> Optional.of(versionConstraint('null'))

        when:
        LoaderDependencyPolicy.INSTANCE.addC2meRuntime(
            project,
            MultiloaderProjectContext.of(project),
            catalog,
            identity(MultiloaderProjectRole.NEOFORGE),
            LoaderId.NEOFORGE,
            '26.2-rc-1',
        )

        then:
        project.configurations.runtimeOnly.allDependencies.empty
        0 * catalog.findLibrary(_)
    }

    def "legacy Fabric skips a catalogued null C2ME version"() {
        given:
        def project = javaProject()
        project.configurations.maybeCreate('modLocalRuntime')
        def catalog = Mock(VersionCatalog)
        catalog.findVersion('c2me-fabric') >> Optional.of(versionConstraint('null'))

        when:
        LoaderDependencyPolicy.INSTANCE.addC2meRuntime(
            project,
            MultiloaderProjectContext.of(project),
            catalog,
            identity(MultiloaderProjectRole.FABRIC),
            LoaderId.FABRIC,
            '1.16.5',
        )

        then:
        project.configurations.modLocalRuntime.allDependencies.empty
        0 * catalog.findLibrary(_)
    }

    private def javaProject() {
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        project.pluginManager.apply('java-library')
        project
    }

    private VersionCatalog c2meCatalog(def project, String alias, String version) {
        def catalog = Mock(VersionCatalog)
        catalog.findVersion(alias) >> Optional.of(versionConstraint(version))
        catalog.findLibrary(alias) >> Optional.of(project.providers.provider {
            project.dependencies.create("example:$alias:$version")
        })
        catalog
    }

    private VersionConstraint versionConstraint(String version) {
        def constraint = Mock(VersionConstraint)
        constraint.requiredVersion >> version
        constraint
    }

    private ProjectIdentity identity(MultiloaderProjectRole role) {
        new ProjectIdentity(
            'example',
            '1.0.0',
            'example',
            'Example',
            'iamkaf',
            '26.2',
            21,
            role,
        )
    }
}
