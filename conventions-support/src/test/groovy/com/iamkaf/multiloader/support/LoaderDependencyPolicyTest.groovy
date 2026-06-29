package com.iamkaf.multiloader.support

import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

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
}
