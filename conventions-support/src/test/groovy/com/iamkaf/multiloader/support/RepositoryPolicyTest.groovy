package com.iamkaf.multiloader.support

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.util.concurrent.CopyOnWriteArrayList

class RepositoryPolicyTest extends Specification {
    @TempDir File projectDir

    @Unroll
    def "#scope repositories keep #location workspace dependencies off third-party servers"() {
        given:
        def requests = new CopyOnWriteArrayList<String>()
        def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        def coordinate = 'com.iamkaf.amber:amber-common:11.1.2+1.20.1'
        def pom = '<project><modelVersion>4.0.0</modelVersion><groupId>com.iamkaf.amber</groupId><artifactId>amber-common</artifactId><version>11.1.2+1.20.1</version></project>'
        server.createContext('/') { exchange ->
            def path = exchange.requestURI.path
            requests.add(path)
            boolean workspace = path.contains('/com/iamkaf/')
            boolean available = workspace ? path.startsWith('/kaf/') && location == 'remote' : path.startsWith('/foreign/')
            int status = workspace && path.startsWith('/foreign/') ? 502 : available ? 200 : 404
            def externalPom = '<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>external</artifactId><version>1</version></project>'
            byte[] body = (path.endsWith('.pom') ? (workspace ? pom : externalPom) : 'artifact').bytes
            if (exchange.requestMethod == 'HEAD' || status != 200) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.length)
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        server.start()
        def local = new File(projectDir, 'local-maven')
        if (location == 'local') {
            def directory = new File(local, 'com/iamkaf/amber/amber-common/11.1.2+1.20.1')
            directory.mkdirs()
            new File(directory, 'amber-common-11.1.2+1.20.1.pom').text = pom
            new File(directory, 'amber-common-11.1.2+1.20.1.jar').text = 'artifact'
        }
        def classes = new File(RepositoryPolicy.protectionDomain.codeSource.location.toURI())
        def port = server.address.port
        def classpath = "buildscript { dependencies { classpath files(${JsonOutput.toJson(classes.absolutePath)}) } }\n"
        def policy = scope == 'project' ? 'configureProjectRepositories(project)' : 'configureWorkspaceRepositories(repos)'
        def repositories = """
            def repos = ${scope == 'project' ? 'project.repositories' : 'dependencyResolutionManagement.repositories'}
            repos.maven { name = 'Earlier third party'; url = 'http://127.0.0.1:${port}/foreign/'; allowInsecureProtocol = true }
            com.iamkaf.multiloader.support.RepositoryPolicy.INSTANCE.${policy}
            repos.maven { name = 'Later third party'; url = 'http://127.0.0.1:${port}/foreign/'; allowInsecureProtocol = true }
            repos.each { repository ->
                if (repository.name == 'MavenLocal') {
                    repository.url = uri(${JsonOutput.toJson(local.absolutePath)})
                } else {
                    repository.url = uri('http://127.0.0.1:${port}/' + (repository.name == 'Kaf Maven' ? 'kaf/' : 'foreign/'))
                    repository.allowInsecureProtocol = true
                }
            }
        """
        new File(projectDir, 'settings.gradle').text = scope == 'settings' ? classpath + repositories : ''
        new File(projectDir, 'build.gradle').text = (scope == 'project' ? classpath + repositories : '') + """
            configurations { probe }
            dependencies { probe '${coordinate}'; probe 'org.example:external:1' }
            tasks.register('resolveProbe') { doLast { println(configurations.probe.files*.name.sort()) } }
        """
        def runner = GradleRunner.create().withProjectDir(projectDir).withArguments('resolveProbe', '--stacktrace')

        when:
        def result = location == 'missing' ? runner.buildAndFail() : runner.build()

        then:
        !requests.any { it.startsWith('/foreign/com/iamkaf/') }
        requests.any { it.startsWith('/foreign/org/example/') }
        location == 'local' ? !requests.any { it.startsWith('/kaf/com/iamkaf/') } : requests.any { it.startsWith('/kaf/com/iamkaf/') }
        location == 'missing' ? result.output.contains("Could not find ${coordinate}") : result.output.contains('amber-common-11.1.2+1.20.1.jar')

        cleanup:
        server?.stop(0)

        where:
        [scope, location] << [['project', 'settings'], ['local', 'remote', 'missing']].combinations()
    }
}
