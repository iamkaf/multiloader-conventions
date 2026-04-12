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
    String lastAuthorizationHeader

    def cleanup() {
        server?.stop(0)
    }

    def "downloadTranslations downloads approved locales, preserves en_us, preserves stale files, and overwrites fetched locales"() {
        given:
        startServer()
        routeJson('/api/export/demo-mod', [
            default_locale: 'en_us',
            locales       : [
                [locale: 'en_us', is_source: true],
                [locale: 'fr_fr', is_source: false],
                [locale: 'zh_cn', is_source: false],
            ],
        ])
        routeRaw('/api/export/demo-mod/fr_fr', '{"hello":"Bonjour"}')
        routeRaw('/api/export/demo-mod/zh_cn', '{"hello":"你好"}')
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'demo-mod'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
    baseUrl = '${baseUrl}'
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

    def "downloadTranslations authenticates private exports with translations.token"() {
        given:
        startServer()
        routeAuthJson('/api/export/private-mod', 'kaf_secret', [
            default_locale: 'en_us',
            locales       : [
                [locale: 'en_us', is_source: true],
                [locale: 'pt_br', is_source: false],
            ],
        ])
        routeAuthRaw('/api/export/private-mod/pt_br', 'kaf_secret', '{"hello":"Oi"}')
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'private-mod'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
    baseUrl = '${baseUrl}'
    token = providers.gradleProperty('translations.token')
}
""")

        when:
        def result = gradleRunner('downloadTranslations', '-Ptranslations.token=kaf_secret').build()

        then:
        result.task(':downloadTranslations').outcome == TaskOutcome.SUCCESS
        langFile('pt_br.json').text == '{"hello":"Oi"}'
        lastAuthorizationHeader == 'Bearer kaf_secret'
    }

    def "downloadTranslations fails with a clear message when private exports are missing auth"() {
        given:
        startServer()
        routeStatus('/api/export/private-mod', 401)
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'private-mod'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
    baseUrl = '${baseUrl}'
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('If this project is private, set translations.token or I18N_TOKEN.')
    }

    def "downloadTranslations succeeds when only the source locale exists remotely"() {
        given:
        startServer()
        routeJson('/api/export/demo-mod', [
            default_locale: 'en_us',
            locales       : [
                [locale: 'en_us', is_source: true],
            ],
        ])
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'demo-mod'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
    baseUrl = '${baseUrl}'
}
""")
        writeLangFile('en_us.json', '{"hello":"Hello"}')

        when:
        def result = gradleRunner('downloadTranslations').build()

        then:
        result.task(':downloadTranslations').outcome == TaskOutcome.SUCCESS
        langFile('en_us.json').text == '{"hello":"Hello"}'
        !langFile('fr_fr.json').exists()
        result.output.contains('No non-source locales available')
    }

    def "downloadTranslations fails on malformed index JSON"() {
        given:
        startServer()
        routeRaw('/api/export/demo-mod', '{not-json')
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'demo-mod'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
    baseUrl = '${baseUrl}'
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('Invalid JSON from')
        result.output.contains('/api/export/demo-mod')
    }

    def "downloadTranslations fails on malformed locale JSON"() {
        given:
        startServer()
        routeJson('/api/export/demo-mod', [
            default_locale: 'en_us',
            locales       : [
                [locale: 'en_us', is_source: true],
                [locale: 'fr_fr', is_source: false],
            ],
        ])
        routeRaw('/api/export/demo-mod/fr_fr', '{not-json')
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'demo-mod'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
    baseUrl = '${baseUrl}'
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('Invalid JSON from')
        result.output.contains('/api/export/demo-mod/fr_fr')
    }

    def "plugin fails immediately when applied to a subproject"() {
        given:
        new File(testProjectDir, 'settings.gradle').text = '''
rootProject.name = 'translations-subproject-test'
include('child')
'''.stripIndent()
        new File(testProjectDir, 'build.gradle').text = ''
        def childBuild = new File(testProjectDir, 'child/build.gradle')
        childBuild.parentFile.mkdirs()
        childBuild.text = '''
plugins {
    id 'com.iamkaf.multiloader.translations'
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
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/demo/lang')
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('multiloaderTranslations.projectSlug must be configured.')
    }

    def "downloadTranslations validates that outputDir is configured"() {
        given:
        writeProject("""
plugins {
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'demo-mod'
}
""")

        when:
        def result = gradleRunner('downloadTranslations').buildAndFail()

        then:
        result.output.contains('multiloaderTranslations.outputDir must be configured.')
    }

    private void startServer() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { HttpExchange exchange ->
            lastAuthorizationHeader = exchange.requestHeaders.getFirst('Authorization')
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

    private void routeStatus(String path, int status) {
        routes[path] = { HttpExchange exchange ->
            sendResponse(exchange, status, '')
        }
    }

    private void routeAuthJson(String path, String token, Object payload) {
        routeAuthRaw(path, token, groovy.json.JsonOutput.toJson(payload))
    }

    private void routeAuthRaw(String path, String token, String body) {
        routes[path] = { HttpExchange exchange ->
            def auth = exchange.requestHeaders.getFirst('Authorization')
            if (auth != "Bearer ${token}") {
                sendResponse(exchange, 401, '')
                return
            }
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
        new File(testProjectDir, 'settings.gradle').text = '''
rootProject.name = 'translations-test'
'''.stripIndent()
        new File(testProjectDir, 'build.gradle').text = buildGradle.stripIndent()
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
