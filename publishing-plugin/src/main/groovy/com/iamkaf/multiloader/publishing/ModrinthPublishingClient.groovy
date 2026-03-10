package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class ModrinthPublishingClient {

    static final URI API = URI.create('https://api.modrinth.com/v2')

    private final HttpClient http
    private final String token

    ModrinthPublishingClient(String token) {
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        this.token = token
    }

    String resolveProjectId(String idOrSlug) {
        def request = HttpRequest.newBuilder(API.resolve("/project/${idOrSlug}"))
            .header('User-Agent', 'multiloader-publishing/0.1 (https://github.com/iamkaf/multiloader-conventions)')
            .GET()
            .build()

        def response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw new IllegalStateException("[Publishing] Modrinth project resolution failed for '${idOrSlug}' (HTTP ${response.statusCode()}): ${response.body()}")
        }

        def json = new JsonSlurper().parseText(response.body()) as Map
        def id = json.id?.toString()
        if (!id) {
            throw new IllegalStateException("[Publishing] Modrinth project response was missing an id for '${idOrSlug}'")
        }
        id
    }

    Map createVersionMultipart(Map versionData, List<FilePart> files, boolean debug) {
        String boundary = "multiloader-publishing-${UUID.randomUUID()}"

        byte[] body = MultipartBuilder.build(boundary, [
            new MultipartBuilder.Part(
                name: 'data',
                filename: null,
                contentType: 'application/json',
                bytes: JsonOutput.toJson(versionData).getBytes(StandardCharsets.UTF_8)
            ),
        ] + files.collect { part ->
            new MultipartBuilder.Part(
                name: part.partName,
                filename: part.filename,
                contentType: part.contentType,
                bytes: part.bytes
            )
        })

        if (debug) {
            return [
                boundary   : boundary,
                versionData: versionData,
                files      : files.collect { [part: it.partName, filename: it.filename, bytes: it.bytes.length] },
                bodyBytes  : body.length,
            ]
        }

        def request = HttpRequest.newBuilder(API.resolve('/version'))
            .header('User-Agent', 'multiloader-publishing/0.1 (https://github.com/iamkaf/multiloader-conventions)')
            .header('Authorization', token)
            .header('Content-Type', "multipart/form-data; boundary=${boundary}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        def response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw new IllegalStateException("[Publishing] Modrinth version creation failed (HTTP ${response.statusCode()}): ${response.body()}")
        }

        new JsonSlurper().parseText(response.body()) as Map
    }

    static List<FilePart> filePartsFrom(List<File> files) {
        files.collect { file ->
            if (!file.exists()) {
                throw new IllegalStateException("[Publishing] Modrinth file does not exist: ${file}")
            }

            new FilePart(
                file.name,
                file.name,
                guessContentType(file),
                Files.readAllBytes(file.toPath())
            )
        }
    }

    private static String guessContentType(File file) {
        def name = file.name.toLowerCase(Locale.ROOT)
        if (name.endsWith('.jar')) {
            return 'application/java-archive'
        }
        if (name.endsWith('.zip') || name.endsWith('.mrpack')) {
            return 'application/zip'
        }
        'application/octet-stream'
    }

    static final class FilePart {
        final String partName
        final String filename
        final String contentType
        final byte[] bytes

        FilePart(String partName, String filename, String contentType, byte[] bytes) {
            this.partName = partName
            this.filename = filename
            this.contentType = contentType
            this.bytes = bytes
        }
    }

    static final class MultipartBuilder {
        static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8)

        static byte[] build(String boundary, List<Part> parts) {
            def out = new ByteArrayOutputStream()
            parts.each { part ->
                out.write("--${boundary}".getBytes(StandardCharsets.UTF_8))
                out.write(CRLF)

                def disposition = "Content-Disposition: form-data; name=\"${part.name}\""
                if (part.filename != null) {
                    disposition += "; filename=\"${part.filename}\""
                }
                out.write(disposition.getBytes(StandardCharsets.UTF_8))
                out.write(CRLF)

                if (part.contentType != null) {
                    out.write("Content-Type: ${part.contentType}".getBytes(StandardCharsets.UTF_8))
                    out.write(CRLF)
                }

                out.write(CRLF)
                out.write(part.bytes)
                out.write(CRLF)
            }

            out.write("--${boundary}--".getBytes(StandardCharsets.UTF_8))
            out.write(CRLF)
            out.toByteArray()
        }

        static final class Part {
            String name
            String filename
            String contentType
            byte[] bytes
        }
    }
}
