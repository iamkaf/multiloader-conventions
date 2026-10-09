package com.iamkaf.multiloader.translations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.StandardCharsets
import java.net.InetSocketAddress

class MultiloaderTranslationsPluginFunctionalTest extends Specification {

    @TempDir
    File testProjectDir

    HttpServer server
    String baseUrl
    Map<String, Closure<Void>> routes = [:]

    def cleanup() {
        server?.stop(0)
    }

    def "downloadTranslations downloads approved locales, preserves en_us, preserves stale files, and overwrites fetched locales"() {
        given:
        startServer()
        routeJson('/api/translate/export/demo-mod', [locales: ['fr_fr', 'zh_cn']])
        routeRaw('/api/translate/export/demo-mod/fr_fr', '{"hello":"Bonjour"}')
        routeRaw('/api/translate/export/demo-mod/zh_cn', '{"hello":"你好"}')
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
    baseUrl.set("${baseUrl}")
}
""")
        writeLangFile('en_us.json', '{"hello":"Hello"}')
        writeLangFile('de_de.json', '{"hello":"Hallo"}')
        writeLangFile('fr_fr.json', '{"hello":"Old"}')

        when:
        def result = gradleRunner('downloadTranslations').build()

        then:
        result.task(':downloadTranslations').outcome == TaskOutcome.SUCCESS
        langFile('en_us.json').text == '{"hello":"Hello"}'
        langFile('de_de.json').text == '{"hello":"Hallo"}'
        langFile('fr_fr.json').text == '{"hello":"Bonjour"}'
        langFile('zh_cn.json').text == '{"hello":"你好"}'
    }

    def "downloadTranslations accepts Minecraft locale codes outside the xx_xx shape"() {
        given:
        startServer()
        routeJson('/api/translate/export/demo-mod', [locales: ['tok', 'zlm_arab']])
        routeRaw('/api/translate/export/demo-mod/tok', '{"hello":"toki"}')
        routeRaw('/api/translate/export/demo-mod/zlm_arab', '{"hello":"هلو"}')
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
    baseUrl.set("${baseUrl}")
}
""")

        when:
        def result = gradleRunner('downloadTranslations').build()

        then:
        result.task(':downloadTranslations').outcome == TaskOutcome.SUCCESS
        langFile('tok.json').text == '{"hello":"toki"}'
        langFile('zlm_arab.json').text == '{"hello":"هلو"}'
    }

    def "downloadTranslations refuses locale codes that would escape the lang directory"() {
        given:
        startServer()
        routeJson('/api/translate/export/demo-mod', [locales: ['../evil']])
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
    baseUrl.set("${baseUrl}")
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains("'../evil' is not a Minecraft locale code")
        !new File(testProjectDir, 'common/src/main/resources/assets/demo/evil.json').exists()
    }

    def "downloadTranslations succeeds when nothing is translated yet"() {
        given:
        startServer()
        routeJson('/api/translate/export/demo-mod', [locales: []])
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
    baseUrl.set("${baseUrl}")
}
""")
        writeLangFile('en_us.json', '{"hello":"Hello"}')

        when:
        def result = gradleRunner('downloadTranslations').build()

        then:
        result.task(':downloadTranslations').outcome == TaskOutcome.SUCCESS
        langFile('en_us.json').text == '{"hello":"Hello"}'
        !langFile('fr_fr.json').exists()
        result.output.contains('No translations available')
    }

    def "downloadTranslations fails on malformed index JSON"() {
        given:
        startServer()
        routeRaw('/api/translate/export/demo-mod', '{not-json')
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
    baseUrl.set("${baseUrl}")
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('Invalid JSON from')
        result.output.contains('/api/translate/export/demo-mod')
    }

    def "downloadTranslations fails on malformed locale JSON"() {
        given:
        startServer()
        routeJson('/api/translate/export/demo-mod', [locales: ['fr_fr']])
        routeRaw('/api/translate/export/demo-mod/fr_fr', '{not-json')
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
    baseUrl.set("${baseUrl}")
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('Invalid JSON from')
        result.output.contains('/api/translate/export/demo-mod/fr_fr')
    }

    def "plugin fails immediately when applied to a subproject"() {
        given:
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "translations-subproject-test"
include("child")
'''.stripIndent()
        new File(testProjectDir, 'build.gradle.kts').text = ''
        def childBuild = new File(testProjectDir, 'child/build.gradle.kts')
        childBuild.parentFile.mkdirs()
        childBuild.text = '''
plugins {
    id("com.iamkaf.multiloader.translations")
}
'''.stripIndent()

        when:
        def result = gradleRunner('help').buildAndFail()

        then:
        result.output.contains('com.iamkaf.multiloader.translations must be applied to the root project only.')
    }

    def "downloadTranslations validates that projectSlug is configured"() {
        given:
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/demo/lang"))
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains("property 'projectSlug' doesn't have a configured value")
    }

    def "downloadTranslations validates that outputDir is configured"() {
        given:
        writeProject("""
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("demo-mod")
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains("property 'outputDir' doesn't have a configured value")
    }

    private void startServer() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { HttpExchange exchange ->
            def handler = routes[exchange.requestURI.path]
            if (handler == null) {
                sendResponse(exchange, 404, 'Not found')
                return
            }
            handler.call(exchange)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    private void routeJson(String path, Object payload) {
        routeRaw(path, groovy.json.JsonOutput.toJson(payload))
    }

    private void routeRaw(String path, String body) {
        routes[path] = { HttpExchange exchange ->
            exchange.responseHeaders.add('Content-Type', 'application/json')
            sendResponse(exchange, 200, body)
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String body) {
        def bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.length)
        exchange.responseBody.withCloseable { it.write(bytes) }
    }

    private void writeProject(String buildGradle) {
        new File(testProjectDir, 'settings.gradle.kts').text = '''
rootProject.name = "translations-test"
'''.stripIndent()
        new File(testProjectDir, 'build.gradle.kts').text = buildGradle.stripIndent()
    }

    private File langFile(String name) {
        new File(testProjectDir, "common/src/main/resources/assets/demo/lang/${name}")
    }

    private void writeLangFile(String name, String contents) {
        def file = langFile(name)
        file.parentFile.mkdirs()
        file.text = contents
    }

    private GradleRunner gradleRunner(String... args) {
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments((args as List) + ['--stacktrace'])
    }
}
