package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class ModrinthPublishingClient(private val token: String) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun resolveProjectId(idOrSlug: String): String {
        val request = HttpRequest.newBuilder(API.resolve("project/$idOrSlug"))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw IllegalStateException(
                "[Publishing] Modrinth project resolution failed for '$idOrSlug' (HTTP ${response.statusCode()}): ${response.body()}",
            )
        }

        val json = JsonSlurper().parseText(response.body()) as Map<*, *>
        val id = json["id"]?.toString()
        if (id.isNullOrBlank()) {
            throw IllegalStateException("[Publishing] Modrinth project response was missing an id for '$idOrSlug'")
        }
        return id
    }

    fun createVersionMultipart(
        versionData: Map<String, Any?>,
        files: List<FilePart>,
        debug: Boolean,
    ): Map<String, Any?> {
        val boundary = "multiloader-publishing-${UUID.randomUUID()}"
        val parts = listOf(
            MultipartBuilder.Part(
                name = "data",
                filename = null,
                contentType = "application/json",
                bytes = JsonOutput.toJson(versionData).toByteArray(StandardCharsets.UTF_8),
            ),
        ) + files.map { part ->
            MultipartBuilder.Part(
                name = part.partName,
                filename = part.filename,
                contentType = part.contentType,
                bytes = part.bytes,
            )
        }
        val body = MultipartBuilder.build(boundary, parts)

        if (debug) {
            return linkedMapOf(
                "boundary" to boundary,
                "versionData" to versionData,
                "files" to files.map { mapOf("part" to it.partName, "filename" to it.filename, "bytes" to it.bytes.size) },
                "bodyBytes" to body.size,
            )
        }

        val request = HttpRequest.newBuilder(API.resolve("version"))
            .header("User-Agent", USER_AGENT)
            .header("Authorization", token)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw IllegalStateException("[Publishing] Modrinth version creation failed (HTTP ${response.statusCode()}): ${response.body()}")
        }

        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parseText(response.body()) as Map<String, Any?>
    }

    class FilePart(
        val partName: String,
        val filename: String,
        val contentType: String,
        val bytes: ByteArray,
    )

    object MultipartBuilder {
        @JvmField
        val CRLF: ByteArray = "\r\n".toByteArray(StandardCharsets.UTF_8)

        @JvmStatic
        fun build(boundary: String, parts: List<Part>): ByteArray {
            val out = ByteArrayOutputStream()
            parts.forEach { part ->
                out.write("--$boundary".toByteArray(StandardCharsets.UTF_8))
                out.write(CRLF)

                var disposition = "Content-Disposition: form-data; name=\"${part.name}\""
                if (part.filename != null) {
                    disposition += "; filename=\"${part.filename}\""
                }
                out.write(disposition.toByteArray(StandardCharsets.UTF_8))
                out.write(CRLF)

                if (part.contentType != null) {
                    out.write("Content-Type: ${part.contentType}".toByteArray(StandardCharsets.UTF_8))
                    out.write(CRLF)
                }

                out.write(CRLF)
                out.write(part.bytes)
                out.write(CRLF)
            }

            out.write("--$boundary--".toByteArray(StandardCharsets.UTF_8))
            out.write(CRLF)
            return out.toByteArray()
        }

        class Part() {
            lateinit var name: String
            var filename: String? = null
            var contentType: String? = null
            var bytes: ByteArray = ByteArray(0)

            constructor(name: String, filename: String?, contentType: String?, bytes: ByteArray) : this() {
                this.name = name
                this.filename = filename
                this.contentType = contentType
                this.bytes = bytes
            }
        }
    }

    companion object {
        @JvmField
        val API: URI = URI.create("https://api.modrinth.com/v2/")

        const val USER_AGENT = "multiloader-publishing/0.1 (https://github.com/iamkaf/multiloader-conventions)"

        @JvmStatic
        fun filePartsFrom(files: List<File>): List<FilePart> =
            files.map { file ->
                if (!file.exists()) {
                    throw IllegalStateException("[Publishing] Modrinth file does not exist: $file")
                }

                FilePart(
                    file.name,
                    file.name,
                    guessContentType(file),
                    Files.readAllBytes(file.toPath()),
                )
            }

        private fun guessContentType(file: File): String {
            val name = file.name.lowercase(Locale.ROOT)
            return when {
                name.endsWith(".jar") -> "application/java-archive"
                name.endsWith(".zip") || name.endsWith(".mrpack") -> "application/zip"
                else -> "application/octet-stream"
            }
        }
    }
}
