package com.iamkaf.multiloader.translations

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Locale

class I18nExportClient {
    private val httpClient: HttpClient
    private val baseUri: URI
    private val token: String?

    constructor(baseUrl: String, token: String?) : this(HttpClient.newHttpClient(), toBaseUri(baseUrl), token)

    constructor(httpClient: HttpClient, baseUri: URI, token: String?) {
        this.httpClient = httpClient
        this.baseUri = baseUri
        this.token = token
    }

    fun fetchProjectIndex(projectSlug: String): ProjectExportIndex {
        val response = getJson("api/export/${encode(projectSlug)}", "project export index for '$projectSlug'")
        val payload = response.payload as? Map<*, *>
            ?: throw GradleException("Invalid JSON from ${response.uri}: expected an object for the project export index.")
        val localesValue = payload["locales"] as? List<*>
            ?: throw GradleException("Invalid JSON from ${response.uri}: expected 'locales' to be an array.")

        val locales = localesValue.map { item ->
            val itemMap = item as? Map<*, *>
                ?: throw GradleException("Invalid JSON from ${response.uri}: expected every locale entry to be an object.")
            val locale = normalizeLocale(itemMap["locale"])
                ?: throw GradleException("Invalid JSON from ${response.uri}: locale entries must contain a locale matching xx_xx.")
            ProjectLocale(locale, itemMap["is_source"] == true)
        }

        return ProjectExportIndex(normalizeLocale(payload["default_locale"]), locales)
    }

    fun fetchLocaleExport(projectSlug: String, locale: String): LocaleExport {
        val response = getJson(
            "api/export/${encode(projectSlug)}/${encode(locale)}",
            "locale export for '$projectSlug/$locale'",
        )

        val payload = response.payload as? Map<*, *>
            ?: throw GradleException("Invalid JSON from ${response.uri}: expected an object for the locale export.")

        @Suppress("UNCHECKED_CAST")
        return LocaleExport(response.rawBody, payload as Map<String, Any?>)
    }

    private fun getJson(relativePath: String, description: String): JsonResponse {
        val uri = baseUri.resolve(relativePath)
        val requestBuilder = HttpRequest.newBuilder(uri)
            .GET()
            .header("Accept", "application/json")

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (exception is IOException || exception is InterruptedException) {
                throw GradleException("Failed to fetch $description from $uri: ${exception.message}", exception)
            }
            throw exception
        }

        if (response.statusCode() !in 200..299) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw GradleException(
                    "Failed to fetch $description from $uri: HTTP ${response.statusCode()}. " +
                        "If this project is private, set translations.token or I18N_TOKEN.",
                )
            }

            throw GradleException("Failed to fetch $description from $uri: HTTP ${response.statusCode()}.")
        }

        return try {
            JsonResponse(uri, response.body(), JsonSlurper().parseText(response.body()))
        } catch (exception: Exception) {
            throw GradleException("Invalid JSON from $uri: ${exception.message}", exception)
        }
    }

    data class ProjectExportIndex(val defaultLocale: String?, val locales: List<ProjectLocale>)

    data class ProjectLocale(val locale: String, val source: Boolean)

    data class LocaleExport(val rawBody: String, val payload: Map<String, Any?>)

    private data class JsonResponse(val uri: URI, val rawBody: String, val payload: Any?)

    private companion object {
        fun toBaseUri(baseUrl: String?): URI {
            val trimmed = baseUrl?.trim().orEmpty()
            if (trimmed.isBlank()) {
                throw GradleException("multiloaderTranslations.baseUrl must not be blank.")
            }
            return URI.create(if (trimmed.endsWith("/")) trimmed else "$trimmed/")
        }

        fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun normalizeLocale(value: Any?): String? {
            if (value !is CharSequence) return null

            val normalized = value.toString().trim().lowercase(Locale.ROOT)
            return if (Regex("[a-z]{2}_[a-z]{2}").matches(normalized)) normalized else null
        }
    }
}
