package com.iamkaf.multiloader.support

import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class ClientRunEnvironmentPolicyTest extends Specification {

    @TempDir
    File projectDir

    def "Forge-like client JavaExec tasks prefer X11 on Linux Wayland hosts"() {
        given:
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        def runClient = project.tasks.create('runClient', JavaExec)
        runClient.environment('WAYLAND_DISPLAY', 'wayland-1')
        runClient.environment.remove('GLFW_PLATFORM')
        def runServer = project.tasks.create('runServer', JavaExec)
        runServer.environment('WAYLAND_DISPLAY', 'wayland-1')
        runServer.environment.remove('GLFW_PLATFORM')

        when:
        ClientRunEnvironmentPolicy.INSTANCE.configureForgeLikeClientRuns(
            project,
            new ClientRunEnvironmentPolicy.HostEnvironment('Linux', 'wayland-1', 'wayland'),
        )

        then:
        runClient.systemProperties['java.net.preferIPv6Addresses'] == 'false'
        !runClient.environment.containsKey('WAYLAND_DISPLAY')
        runClient.environment['XDG_SESSION_TYPE'] == 'x11'
        runClient.environment['GLFW_PLATFORM'] == 'x11'

        runServer.environment['WAYLAND_DISPLAY'] == 'wayland-1'
        !runServer.environment.containsKey('GLFW_PLATFORM')
    }

    def "Forge-like client JavaExec tasks are left alone off Wayland"() {
        given:
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        def runClient = project.tasks.create('runClient', JavaExec)
        runClient.environment('WAYLAND_DISPLAY', 'wayland-1')
        runClient.environment.remove('GLFW_PLATFORM')

        when:
        ClientRunEnvironmentPolicy.INSTANCE.configureForgeLikeClientRuns(
            project,
            new ClientRunEnvironmentPolicy.HostEnvironment('Linux', null, 'x11'),
        )

        then:
        runClient.systemProperties['java.net.preferIPv6Addresses'] == 'false'
        runClient.environment['WAYLAND_DISPLAY'] == 'wayland-1'
        !runClient.environment.containsKey('GLFW_PLATFORM')
    }

    def "Forge-like client JavaExec tasks normalize ModDev IPv6 VM args"() {
        given:
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        def runClient = project.tasks.create('runClient', JavaExec)
        def vmArgs = new File(project.buildDir, 'moddev/clientRunVmArgs.txt')
        vmArgs.parentFile.mkdirs()
        vmArgs.text = '''\
-Djava.net.preferIPv6Addresses=system
-Dexample=true
'''

        when:
        ClientRunEnvironmentPolicy.INSTANCE.configureForgeLikeClientRuns(
            project,
            new ClientRunEnvironmentPolicy.HostEnvironment('Linux', null, 'x11'),
        )
        runClient.actions.first().execute(runClient)

        then:
        vmArgs.readLines().contains('-Djava.net.preferIPv6Addresses=false')
        !vmArgs.readLines().contains('-Djava.net.preferIPv6Addresses=system')
        vmArgs.readLines().contains('-Dexample=true')
    }

    def "Groovy run metadata receives X11 hints on Linux Wayland hosts"() {
        given:
        def project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        def run = new FakeRun()

        when:
        ClientRunEnvironmentPolicy.INSTANCE.applyToGroovyClientRun(
            project,
            run,
            new ClientRunEnvironmentPolicy.HostEnvironment('Linux', 'wayland-1', 'wayland'),
        )

        then:
        run.environmentCalls['XDG_SESSION_TYPE'] == 'x11'
        run.environmentCalls['GLFW_PLATFORM'] == 'x11'
        !run.environmentVariables.containsKey('WAYLAND_DISPLAY')
        run.environmentVariables['XDG_SESSION_TYPE'] == 'x11'
        run.environmentVariables['GLFW_PLATFORM'] == 'x11'
    }

    def "Wayland detection is Linux-specific"() {
        expect:
        ClientRunEnvironmentPolicy.INSTANCE.shouldPreferX11OnWayland(
            new ClientRunEnvironmentPolicy.HostEnvironment(osName, waylandDisplay, sessionType),
        ) == expected

        where:
        osName    | waylandDisplay | sessionType || expected
        'Linux'   | 'wayland-1'    | 'wayland'   || true
        'Linux'   | null           | 'wayland'   || true
        'Linux'   | null           | 'x11'       || false
        'Mac OS X' | 'wayland-1'    | 'wayland'   || false
        'Windows' | 'wayland-1'    | 'wayland'   || false
    }

    private static class FakeRun {
        Map environmentVariables = ['WAYLAND_DISPLAY': 'wayland-1']
        Map environmentCalls = [:]

        void environment(String key, String value) {
            environmentCalls[key] = value
        }
    }
}
