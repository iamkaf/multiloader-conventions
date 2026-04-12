package com.iamkaf.multiloader.translations

import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

class I18nExportClient {

    private final HttpClient httpClient
    private final URI baseUri
    private final String token

    I18nExportClient(String baseUrl, String token) {
        this(HttpClient.newHttpClient(), toBaseUri(baseUrl), token)
    }

    I18nExportClient(HttpClient httpClient, URI baseUri, String token) {
        this.httpClient = httpClient
        this.baseUri = baseUri
        this.token = token
    }

    ProjectExportIndex fetchProjectIndex(String projectSlug) {
        def response = getJson("api/export/${encode(projectSlug)}", "project export index for '${projectSlug}'")
        if (!(response.payload instanceof Map)) {
            throw new GradleException("Invalid JSON from ${response.uri}: expected an object for the project export index.")
        }

        def payload = (Map) response.payload
        def localesValue = payload.locales
        if (!(localesValue instanceof List)) {
            throw new GradleException("Invalid JSON from ${response.uri}: expected 'locales' to be an array.")
        }

        def locales = localesValue.collect { item ->
            if (!(item instanceof Map)) {
                throw new GradleException("Invalid JSON from ${response.uri}: expected every locale entry to be an object.")
            }

            def locale = normalizeLocale(((Map) item).locale)
            if (locale == null) {
                throw new GradleException("Invalid JSON from ${response.uri}: locale entries must contain a locale matching xx_xx.")
            }

            new ProjectLocale(locale, Boolean.TRUE == ((Map) item).is_source)
        }

        new ProjectExportIndex(normalizeLocale(payload.default_locale), locales)
    }

    LocaleExport fetchLocaleExport(String projectSlug, String locale) {
        def response = getJson(
            "api/export/${encode(projectSlug)}/${encode(locale)}",
            "locale export for '${projectSlug}/${locale}'"
        )

        if (!(response.payload instanceof Map)) {
            throw new GradleException("Invalid JSON from ${response.uri}: expected an object for the locale export.")
        }

        new LocaleExport(response.rawBody, (Map<String, Object>) response.payload)
    }

    private JsonResponse getJson(String relativePath, String description) {
        def uri = baseUri.resolve(relativePath)
        def requestBuilder = HttpRequest.newBuilder(uri)
            .GET()
            .header('Accept', 'application/json')

        if (token != null && !token.isBlank()) {
            requestBuilder.header('Authorization', "Bearer ${token}")
        }

        HttpResponse<String> response
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw new GradleException("Failed to fetch ${description} from ${uri}: ${e.message}", e)
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new GradleException(
                    "Failed to fetch ${description} from ${uri}: HTTP ${response.statusCode()}. " +
                        "If this project is private, set translations.token or I18N_TOKEN."
                )
            }

            throw new GradleException("Failed to fetch ${description} from ${uri}: HTTP ${response.statusCode()}.")
        }

        try {
            new JsonResponse(uri, response.body(), new JsonSlurper().parseText(response.body()))
        } catch (Exception e) {
            throw new GradleException("Invalid JSON from ${uri}: ${e.message}", e)
        }
    }

    private static URI toBaseUri(String baseUrl) {
        def trimmed = baseUrl == null ? '' : baseUrl.trim()
        if (trimmed.isBlank()) {
            throw new GradleException('multiloaderTranslations.baseUrl must not be blank.')
        }
        URI.create(trimmed.endsWith('/') ? trimmed : "${trimmed}/")
    }

    private static String encode(String value) {
        URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private static String normalizeLocale(Object value) {
        if (!(value instanceof CharSequence)) {
            return null
        }

        def normalized = value.toString().trim().toLowerCase(Locale.ROOT)
        normalized ==~ /[a-z]{2}_[a-z]{2}/ ? normalized : null
    }

    static final class ProjectExportIndex {
        final String defaultLocale
        final List<ProjectLocale> locales

        ProjectExportIndex(String defaultLocale, List<ProjectLocale> locales) {
            this.defaultLocale = defaultLocale
            this.locales = locales
        }
    }

    static final class ProjectLocale {
        final String locale
        final boolean source

        ProjectLocale(String locale, boolean source) {
            this.locale = locale
            this.source = source
        }
    }

    static final class LocaleExport {
        final String rawBody
        final Map<String, Object> payload

        LocaleExport(String rawBody, Map<String, Object> payload) {
            this.rawBody = rawBody
            this.payload = payload
        }
    }

    private static final class JsonResponse {
        final URI uri
        final String rawBody
        final Object payload

        JsonResponse(URI uri, String rawBody, Object payload) {
            this.uri = uri
            this.rawBody = rawBody
            this.payload = payload
        }
    }
}
