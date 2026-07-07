package com.iamkaf.multiloader.root

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class TeaKitRunPropertyForwarderTest extends Specification {

    @TempDir
    File projectDir

    private Properties originalSystemProperties

    def setup() {
        originalSystemProperties = new Properties()
        originalSystemProperties.putAll(System.getProperties())
    }

    def cleanup() {
        // Restore system properties so this test cannot leak state to other tests.
        System.setProperties(originalSystemProperties)
    }

    def "collectSystemProperties forwards teakit.* keys"() {
        given:
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        System.setProperty("teakit.enabled", "true")
        System.setProperty("teakit.node", "26.2-fabric")

        expect:
        TeaKitRunPropertyForwarder.INSTANCE.collectSystemProperties(project) == [
            "teakit.enabled": "true",
            "teakit.node": "26.2-fabric",
        ]
    }

    def "collectSystemProperties forwards fabric.noGui as the only non-teakit allowlisted key"() {
        given:
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        System.setProperty("fabric.noGui", "true")
        System.setProperty("teakit.enabled", "true")

        expect:
        def collected = TeaKitRunPropertyForwarder.INSTANCE.collectSystemProperties(project)
        collected == [
            "fabric.noGui": "true",
            "teakit.enabled": "true",
        ]
    }

    def "collectSystemProperties does not become a broad -D pass-through"() {
        given:
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        System.setProperty("teakit.enabled", "true")
        System.setProperty("fabric.noGui", "true")
        // Arbitrary non-TeaKit system properties are dropped.
        System.setProperty("foo.bar", "baz")
        System.setProperty("java.awt.headless", "true")
        System.setProperty("com.example.flag", "true")

        when:
        def collected = TeaKitRunPropertyForwarder.INSTANCE.collectSystemProperties(project)

        then:
        collected.keySet() == ["fabric.noGui", "teakit.enabled"] as Set
        !collected.containsKey("foo.bar")
        !collected.containsKey("java.awt.headless")
        !collected.containsKey("com.example.flag")
    }

    def "collectSystemProperties forwards fabric.noGui even when false is configured"() {
        given:
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        System.setProperty("fabric.noGui", "false")

        expect:
        TeaKitRunPropertyForwarder.INSTANCE.collectSystemProperties(project) == [
            "fabric.noGui": "false",
        ]
    }
}