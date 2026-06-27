package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class CurseForgePublishingClient(private val token: String) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var cachedGameVersions: List<Map<String, Any?>>? = null

    fun uploadFile(projectId: Long, metadata: Map<String, Any?>, file: File): Map<String, Any?> {
        if (!file.exists()) {
            throw IllegalStateException("[Publishing] CurseForge file does not exist: $file")
        }

        val boundary = "multiloader-publishing-${UUID.randomUUID()}"
        val body = ModrinthPublishingClient.MultipartBuilder.build(
            boundary,
            listOf(
                ModrinthPublishingClient.MultipartBuilder.Part(
                    name = "metadata",
                    filename = null,
                    contentType = "application/json",
                    bytes = JsonOutput.toJson(metadata).toByteArray(StandardCharsets.UTF_8),
                ),
                ModrinthPublishingClient.MultipartBuilder.Part(
                    name = "file",
                    filename = file.name,
                    contentType = guessContentType(file),
                    bytes = Files.readAllBytes(file.toPath()),
                ),
            ),
        )

        val request = HttpRequest.newBuilder(API.resolve("/api/projects/$projectId/upload-file"))
            .header("User-Agent", ModrinthPublishingClient.USER_AGENT)
            .header("X-Api-Token", token)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw IllegalStateException("[Publishing] CurseForge upload failed (HTTP ${response.statusCode()}): ${response.body()}")
        }

        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parseText(response.body()) as Map<String, Any?>
    }

    fun resolveGameVersionIds(namesOrSlugs: Collection<String>?): List<Int> {
        if (namesOrSlugs.isNullOrEmpty()) return emptyList()

        val versions = listGameVersions()
        val byName = mutableMapOf<String, Map<String, Any?>>()
        val bySlug = mutableMapOf<String, Map<String, Any?>>()
        versions.forEach { version ->
            version["name"]?.toString()?.let { byName[it.lowercase(Locale.ROOT)] = version }
            version["slug"]?.toString()?.let { bySlug[it.lowercase(Locale.ROOT)] = version }
        }

        val ids = mutableListOf<Int>()
        namesOrSlugs.forEach { raw ->
            val key = raw.trim()
            if (key.isEmpty()) return@forEach

            val normalized = key.lowercase(Locale.ROOT)
            val match = byName[normalized] ?: bySlug[normalized]
                ?: throw IllegalStateException("[Publishing] CurseForge could not resolve '$key' to a game version id")
            ids.add((match["id"] as Number).toInt())
        }

        return ids.distinct()
    }

    fun listGameVersions(): List<Map<String, Any?>> {
        cachedGameVersions?.let { return it }

        val request = HttpRequest.newBuilder(API.resolve("/api/game/versions"))
            .header("User-Agent", ModrinthPublishingClient.USER_AGENT)
            .header("X-Api-Token", token)
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw IllegalStateException("[Publishing] CurseForge game version lookup failed (HTTP ${response.statusCode()}): ${response.body()}")
        }

        val json = JsonSlurper().parseText(response.body())
        if (json !is List<*>) {
            throw IllegalStateException("[Publishing] CurseForge /api/game/versions did not return a list")
        }

        @Suppress("UNCHECKED_CAST")
        val versions = json as List<Map<String, Any?>>
        cachedGameVersions = versions
        return versions
    }

    private fun guessContentType(file: File): String {
        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".jar") -> "application/java-archive"
            name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    companion object {
        @JvmField
        val API: URI = URI.create("https://minecraft.curseforge.com")
    }
}
