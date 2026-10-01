package com.iamkaf.multiloader.paper

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Downloads one pinned Paper server build and refuses it unless the SHA-256 matches the catalog. */
@DisableCachingByDefault(because = "The server jar lives in a shared Gradle user home cache")
abstract class DownloadPaperServerTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Input
    abstract val build: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val apiBaseUrl: Property<String>

    @get:OutputFile
    abstract val serverJar: RegularFileProperty

    @TaskAction
    fun download() {
        val target = serverJar.get().asFile.toPath()
        val expected = sha256.get().lowercase()
        if (Files.exists(target) && sha256Of(Files.readAllBytes(target)) == expected) return

        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val buildUrl = "${apiBaseUrl.get()}/v3/projects/paper/versions/${minecraftVersion.get()}/builds/${build.get()}"
        val url = downloadUrl(client.send(request(buildUrl), HttpResponse.BodyHandlers.ofString()), buildUrl)
        val response = client.send(request(url), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            throw GradleException("Paper server download failed with HTTP ${response.statusCode()}: $url")
        }
        val actual = sha256Of(response.body())
        if (actual != expected) {
            throw GradleException(
                "Paper ${minecraftVersion.get()} build ${build.get()} has SHA-256 $actual, but the catalog pins $expected. " +
                    "Refusing to run it.",
            )
        }

        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        Files.write(partial, response.body())
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun downloadUrl(response: HttpResponse<String>, buildUrl: String): String {
        if (response.statusCode() != 200) {
            throw GradleException("Paper build lookup failed with HTTP ${response.statusCode()}: $buildUrl")
        }
        val build = JsonSlurper().parseText(response.body()) as? Map<*, *>
        val downloads = build?.get("downloads") as? Map<*, *>
        val server = downloads?.get("server:default") as? Map<*, *>
        return server?.get("url") as? String
            ?: throw GradleException("Paper build lookup has no server:default download: $buildUrl")
    }

    // Paper asks API clients to identify themselves.
    private fun request(url: String): HttpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "multiloader-conventions (https://github.com/iamkaf/multiloader-conventions)")
            .build()

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
