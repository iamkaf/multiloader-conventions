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

/** Reads approved translations from kaf.sh Translate. */
class TranslateExportClient(baseUrl: String) {
    private val httpClient = HttpClient.newHttpClient()
    private val baseUri = toBaseUri(baseUrl)

    /** The locales the project has translations for. */
    fun fetchLocales(projectSlug: String): List<String> {
        val response = get("api/translate/export/${encode(projectSlug)}", "locale list for '$projectSlug'")
        val locales = (parseJson(response) as? Map<*, *>)?.get("locales") as? List<*>
            ?: throw GradleException("Invalid JSON from ${response.uri()}: expected an object with a 'locales' array.")

        return locales.map { locale ->
            // Locale codes become file names, so anything else is rejected.
            (locale as? String)?.takeIf { LOCALE.matches(it) }
                ?: throw GradleException("Invalid JSON from ${response.uri()}: '$locale' is not a Minecraft locale code.")
        }
    }

    /** The locale's lang file, exactly as it should be written to disk. */
    fun fetchLangFile(projectSlug: String, locale: String): String {
        val response = get(
            "api/translate/export/${encode(projectSlug)}/${encode(locale)}",
            "'$locale' translations for '$projectSlug'",
        )
        if (parseJson(response) !is Map<*, *>) {
            throw GradleException("Invalid JSON from ${response.uri()}: expected a lang file object.")
        }
        return response.body()
    }

    private fun get(relativePath: String, description: String): HttpResponse<String> {
        val uri = baseUri.resolve(relativePath)
        val request = HttpRequest.newBuilder(uri).GET().header("Accept", "application/json").build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: IOException) {
            throw GradleException("Failed to fetch $description from $uri: ${exception.message}", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("Failed to fetch $description from $uri: ${exception.message}", exception)
        }

        if (response.statusCode() !in 200..299) {
            throw GradleException("Failed to fetch $description from $uri: HTTP ${response.statusCode()}.")
        }
        return response
    }

    private companion object {
        // Minecraft locales include bare and longer codes such as tok, enws, and zlm_arab.
        val LOCALE = Regex("[a-z]+(_[a-z]+)?")

        fun parseJson(response: HttpResponse<String>): Any? =
            try {
                JsonSlurper().parseText(response.body())
            } catch (exception: Exception) {
                throw GradleException("Invalid JSON from ${response.uri()}: ${exception.message}", exception)
            }

        fun toBaseUri(baseUrl: String): URI {
            val trimmed = baseUrl.trim()
            if (trimmed.isBlank()) {
                throw GradleException("multiloaderTranslations.baseUrl must not be blank.")
            }
            return URI.create(if (trimmed.endsWith("/")) trimmed else "$trimmed/")
        }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
