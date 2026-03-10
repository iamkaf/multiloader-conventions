package com.iamkaf.multiloader.publishing

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class CurseForgePublishingClient {

    static final URI API = URI.create('https://minecraft.curseforge.com')

    private final HttpClient http
    private final String token
    private List<Map> cachedGameVersions

    CurseForgePublishingClient(String token) {
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        this.token = token
    }

    Map uploadFile(long projectId, Map metadata, File file) {
        if (!file.exists()) {
            throw new IllegalStateException("[Publishing] CurseForge file does not exist: ${file}")
        }

        String boundary = "multiloader-publishing-${UUID.randomUUID()}"

        byte[] body = ModrinthPublishingClient.MultipartBuilder.build(boundary, [
            new ModrinthPublishingClient.MultipartBuilder.Part(
                name: 'metadata',
                filename: null,
                contentType: 'application/json',
                bytes: JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8)
            ),
            new ModrinthPublishingClient.MultipartBuilder.Part(
                name: 'file',
                filename: file.name,
                contentType: guessContentType(file),
                bytes: Files.readAllBytes(file.toPath())
            )
        ])

        def request = HttpRequest.newBuilder(API.resolve("/api/projects/${projectId}/upload-file"))
            .header('User-Agent', 'multiloader-publishing/0.1 (https://github.com/iamkaf/multiloader-conventions)')
            .header('X-Api-Token', token)
            .header('Content-Type', "multipart/form-data; boundary=${boundary}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        def response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw new IllegalStateException("[Publishing] CurseForge upload failed (HTTP ${response.statusCode()}): ${response.body()}")
        }

        new JsonSlurper().parseText(response.body()) as Map
    }

    List<Integer> resolveGameVersionIds(Collection<String> namesOrSlugs) {
        if (namesOrSlugs == null || namesOrSlugs.isEmpty()) {
            return []
        }

        def versions = listGameVersions()
        def byName = [:]
        def bySlug = [:]
        versions.each { version ->
            def name = version.name?.toString()
            def slug = version.slug?.toString()
            if (name) {
                byName[name.toLowerCase(Locale.ROOT)] = version
            }
            if (slug) {
                bySlug[slug.toLowerCase(Locale.ROOT)] = version
            }
        }

        def ids = [] as List<Integer>
        namesOrSlugs.each { raw ->
            def key = raw?.toString()?.trim()
            if (!key) {
                return
            }

            def normalized = key.toLowerCase(Locale.ROOT)
            def match = byName[normalized] ?: bySlug[normalized]
            if (match == null) {
                throw new IllegalStateException("[Publishing] CurseForge could not resolve '${key}' to a game version id")
            }
            ids.add((match.id as Number).intValue())
        }

        ids.unique()
    }

    List<Map> listGameVersions() {
        if (cachedGameVersions != null) {
            return cachedGameVersions
        }

        def request = HttpRequest.newBuilder(API.resolve('/api/game/versions'))
            .header('User-Agent', 'multiloader-publishing/0.1 (https://github.com/iamkaf/multiloader-conventions)')
            .header('X-Api-Token', token)
            .GET()
            .build()

        def response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw new IllegalStateException("[Publishing] CurseForge game version lookup failed (HTTP ${response.statusCode()}): ${response.body()}")
        }

        def json = new JsonSlurper().parseText(response.body())
        if (!(json instanceof List)) {
            throw new IllegalStateException('[Publishing] CurseForge /api/game/versions did not return a list')
        }

        cachedGameVersions = (List<Map>) json
        cachedGameVersions
    }

    private static String guessContentType(File file) {
        def name = file.name.toLowerCase(Locale.ROOT)
        if (name.endsWith('.jar')) {
            return 'application/java-archive'
        }
        if (name.endsWith('.zip')) {
            return 'application/zip'
        }
        'application/octet-stream'
    }
}
