package com.iamkaf.multiloader.paper

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

import java.net.InetSocketAddress
import java.security.MessageDigest

class MultiloaderPaperPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    HttpServer server

    static final byte[] SERVER_JAR = 'not really a paper server'.bytes

    def cleanup() {
        server?.stop(0)
    }

    def "compiles against the floor catalog's paper-api and expands the plugin version"() {
        given:
        writeProject(sha256(SERVER_JAR), """
tasks.register("printCompileOnly") {
    val coordinates = configurations.compileOnly.get().dependencies.map { "\${it.group}:\${it.name}:\${it.version}" }
    doLast { println("COMPILE_ONLY " + coordinates.joinToString()) }
}
""")
        file('src/main/resources/plugin.yml').text = "name: Demo\nversion: '\${version}'\n"

        when:
        def result = runner('processResources', 'printCompileOnly').build()

        then:
        result.output.contains('COMPILE_ONLY io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT')
        file('build/resources/main/plugin.yml').text == "name: Demo\nversion: '1.2.3'\n"
    }

    def "downloads the pinned server and refuses a jar whose SHA-256 differs"() {
        given:
        startServer()

        when:
        writeProject(sha256(SERVER_JAR))
        def result = runner('downloadPaperServer').build()

        then:
        result.task(':downloadPaperServer').outcome == TaskOutcome.SUCCESS

        when:
        writeProject('0' * 64)
        def failure = runner('downloadPaperServer', '--rerun-tasks').buildAndFail()

        then:
        failure.output.contains('Refusing to run it.')
    }

    def "explains that unlisted Minecraft lines have no loaded catalog"() {
        given:
        writeProject(sha256(SERVER_JAR))

        when:
        def failure = runner('downloadPaperServer', '-Ppaper.minecraft=26.3').buildAndFail()

        then:
        failure.output.contains('Minecraft 26.3 is not listed in plugin.minecraft-versions')
    }

    def "publishes the plugin jar to Modrinth for every server and listed line"() {
        given:
        writeProject(sha256(SERVER_JAR))
        file('gradle.properties') << """
plugin.minecraft-versions=1.21.11,26.3
publish.modrinth.id=demo
publish.dry-run=true
"""
        file('src/main/resources/plugin.yml').text = "name: Demo\nversion: '\${version}'\n"
        file('changelog.md').text = "# Changelog\n\n## 1.2.3\n\n### Added\n\n- First release.\n\n## Types of changes\n"

        when:
        def result = runner('publishModrinth').build()

        then:
        result.task(':publishModrinthPaper').outcome == TaskOutcome.SUCCESS
        def payload = result.output.replaceAll(/\s/, '')
        payload.contains('"loaders":["paper","purpur","folia","spigot"]')
        payload.contains('"game_versions":["1.21.11","26.3"]')
        payload.contains('"name":"demo-1.2.3"')
    }

    def "writes a publishing graph with one paper loader covering every listed line"() {
        given:
        writeProject(sha256(SERVER_JAR))
        file('gradle.properties') << "plugin.minecraft-versions=1.21.11,26.3\npublish.modrinth.id=demo\n"

        when:
        runner('writeMultiloaderGraph').build()
        def graph = new groovy.json.JsonSlurper().parse(file('build/reports/multiloader/graph.json'))

        then:
        graph.versions.size() == 1
        graph.versions[0].name == '1.21.11'
        graph.versions[0].gameVersions == ['1.21.11', '26.3']
        graph.versions[0].loaders[0].name == 'paper'
        graph.versions[0].loaders[0].artifactPath == 'build/libs/demo-1.2.3.jar'
        // CurseForge has no project id here, so only Modrinth is offered.
        graph.versions[0].loaders[0].platformPublishTasks == [modrinth: ':publishModrinthPaper']
    }

    def "refuses to publish a jar without plugin.yml"() {
        given:
        writeProject(sha256(SERVER_JAR))
        file('gradle.properties') << "publish.modrinth.id=demo\npublish.dry-run=true\n"

        when:
        def failure = runner('publishModrinth').buildAndFail()

        then:
        failure.output.contains('no plugin.yml or paper-plugin.yml file was found')
    }

    private void writeProject(String pinnedSha256, String extraBuildScript = '') {
        file('gradle.properties').text = """
project.group=com.example
project.version=1.2.3
project.minecraft=1.21.11
project.java=21
"""
        // Mirrors the subset of a published catalog this plugin reads.
        file('settings.gradle.kts').text = """
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("java", "21")
            version("paper-api", "1.21.11-R0.1-SNAPSHOT")
            version("paper-build", "132")
            version("paper-sha256", "$pinnedSha256")
            library("paper-api", "io.papermc.paper", "paper-api").versionRef("paper-api")
        }
    }
}
rootProject.name = "demo"
"""
        file('build.gradle.kts').text = """
plugins {
    id("com.iamkaf.multiloader.paper")
}

tasks.named<com.iamkaf.multiloader.paper.DownloadPaperServerTask>("downloadPaperServer") {
    apiBaseUrl.set("http://127.0.0.1:${server?.address?.port ?: 1}")
}
$extraBuildScript
"""
    }

    private void startServer() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        def base = "http://127.0.0.1:${server.address.port}"
        server.createContext('/v3/projects/paper/versions/1.21.11/builds/132') { exchange ->
            def body = """{"id":132,"downloads":{"server:default":{"url":"$base/paper.jar"}}}""".bytes
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.createContext('/paper.jar') { exchange ->
            exchange.sendResponseHeaders(200, SERVER_JAR.length)
            exchange.responseBody.withCloseable { it.write(SERVER_JAR) }
        }
        server.start()
    }

    private GradleRunner runner(String... arguments) {
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments(arguments + ['--stacktrace'] as List<String>)
            .withPluginClasspath()
            .forwardOutput()
    }

    private File file(String path) {
        def file = new File(testProjectDir, path)
        file.parentFile.mkdirs()
        file
    }

    private static String sha256(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).collect { String.format('%02x', it) }.join()
    }
}
