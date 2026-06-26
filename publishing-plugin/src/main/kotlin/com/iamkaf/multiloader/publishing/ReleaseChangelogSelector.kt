package com.iamkaf.multiloader.publishing

import org.gradle.api.Project
import java.util.regex.Pattern

internal object ReleaseChangelogSelector {
    fun resolve(
        project: Project,
        extension: MultiloaderPublishingExtension,
        publication: PublicationSpec,
    ): String {
        val finalFilePath = project.findProperty("publish.changelog.final-file")?.toString()
        if (!finalFilePath.isNullOrBlank()) {
            val finalFile = project.file(finalFilePath)
            if (!finalFile.exists()) {
                throw IllegalStateException("[Publishing] Final changelog file not found: $finalFile")
            }
            return finalFile.readText(Charsets.UTF_8)
        }

        if (extension.getMetadata().changelogText.isPresent) {
            return extension.getMetadata().changelogText.get()
        }

        val relPath = extension.getMetadata().changelogFile.orNull
        if (relPath.isNullOrBlank()) {
            throw IllegalStateException("[Publishing] No changelog configured")
        }

        val file = project.file(relPath)
        if (!file.exists()) {
            throw IllegalStateException("[Publishing] Changelog file not found: $file")
        }

        val extracted = extractSelectedChangelog(
            file.readText(Charsets.UTF_8),
            publication.project.version.toString(),
            publication.gameVersions,
        )
        if (extracted.isNullOrBlank()) {
            throw IllegalStateException(
                "[Publishing] Failed to extract changelog ${releaseVersionForChangelog(publication.project.version.toString())} from $file",
            )
        }
        return extracted
    }

    fun extractSelectedChangelog(
        completeChangelog: String,
        projectVersion: String,
        minecraftVersions: List<String>,
    ): String? {
        val parsed = parseChangelog(completeChangelog) ?: return null
        val releaseVersion = releaseVersionForChangelog(projectVersion)
        val release = parsed.releases.firstOrNull { it.version == releaseVersion } ?: return null

        val selectedEntries = release.entries.filter { entry ->
            entry.prefix == null || minecraftVersions.any { version -> prefixMatchesMinecraft(entry.prefix, version) }
        }
        if (selectedEntries.isEmpty()) return null

        val lines = mutableListOf(release.heading)
        val sections = selectedEntries.map { it.section.orEmpty() }.distinct()
        sections.forEach { section ->
            val sectionEntries = selectedEntries.filter { it.section.orEmpty() == section }
            if (sectionEntries.isEmpty()) return@forEach
            lines.add("")
            if (section.isNotEmpty()) {
                lines.add("### $section")
                lines.add("")
            }
            sectionEntries.forEachIndexed { index, entry ->
                if (index > 0) lines.add("")
                lines.addAll(Pattern.compile("\\r?\\n").split(entry.text).toList())
            }
        }

        return listOf(
            trimTrailingBlankLines(Pattern.compile("\\r?\\n").split(parsed.header).toMutableList()).joinToString("\n"),
            trimTrailingBlankLines(lines).joinToString("\n"),
            parsed.footer,
        ).filter(String::isNotEmpty)
            .joinToString("\n\n")
            .trimEnd() + "\n"
    }

    private fun parseChangelog(completeChangelog: String): ParsedChangelog? {
        val lines = Pattern.compile("\\r?\\n").split(completeChangelog, -1).toList()
        val firstReleaseIndex = lines.indexOfFirst { releaseHeading(it) != null }
        val footerIndex = lines.indexOfFirst { it == "## Types of changes" }
        if (firstReleaseIndex < 0 || footerIndex <= firstReleaseIndex) return null

        val header = lines.subList(0, firstReleaseIndex).joinToString("\n")
        val footer = lines.subList(footerIndex, lines.size).joinToString("\n")
        val releases = mutableListOf<ParsedRelease>()
        var current: ParsedRelease? = null

        lines.subList(firstReleaseIndex, footerIndex).forEach { line ->
            val version = releaseHeading(line)
            if (version != null) {
                current = ParsedRelease(line, releaseVersionForChangelog(version), mutableListOf(line))
                releases.add(current!!)
            } else {
                current?.lines?.add(line)
            }
        }

        releases.forEach { release ->
            release.entries.addAll(entriesForRelease(release))
        }
        return ParsedChangelog(header, footer, releases)
    }

    private fun releaseHeading(line: String): String? =
        Regex("^##\\s+(\\d+\\.\\d+\\.\\d+(?:\\+[^\\s]+)?)(?:\\s|$)")
            .find(line)
            ?.groupValues
            ?.get(1)

    private fun entriesForRelease(release: ParsedRelease): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var section: String? = null
        var currentLines = mutableListOf<String>()
        var currentIndex = 0

        fun flush() {
            if (currentLines.isEmpty()) return
            val text = trimTrailingBlankLines(currentLines).joinToString("\n")
            if (text.trim().isNotEmpty()) {
                entries.add(
                    ChangelogEntry(
                        "${release.version}:${section ?: "release"}:$currentIndex",
                        release.version,
                        section,
                        text,
                        versionPrefixForEntry(text),
                    ),
                )
                currentIndex += 1
            }
            currentLines = mutableListOf()
        }

        release.lines.drop(1).forEach { line ->
            val sectionMatcher = Regex("^###\\s+(.+?)\\s*$").find(line)
            when {
                sectionMatcher != null -> {
                    flush()
                    section = sectionMatcher.groupValues[1]
                    currentIndex = 0
                }
                Regex("^[-*+]\\s+.*").matches(line) -> {
                    flush()
                    currentLines = mutableListOf(line)
                }
                currentLines.isNotEmpty() -> currentLines.add(line)
            }
        }
        flush()

        return entries
    }

    private fun versionPrefixForEntry(text: String): String? {
        val firstLine = text.lineSequence().first()
        return Regex("^[-*+]\\s+((?:\\d+\\.\\d+(?:\\.\\d+|\\.x)?|26\\.\\d+(?:\\.\\d+|\\.x)?)):\\s+")
            .find(firstLine)
            ?.groupValues
            ?.get(1)
    }

    private fun prefixMatchesMinecraft(prefix: String, minecraftVersion: String): Boolean =
        if (prefix.endsWith(".x")) minecraftVersion.startsWith(prefix.substring(0, prefix.length - 1)) else prefix == minecraftVersion

    private fun releaseVersionForChangelog(projectVersion: String): String =
        projectVersion.substringBefore("+")

    private fun trimTrailingBlankLines(lines: List<String>): MutableList<String> {
        val result = ArrayList(lines)
        while (result.isNotEmpty() && result.last().trim().isEmpty()) {
            result.removeAt(result.size - 1)
        }
        return result
    }

    private data class ParsedChangelog(
        val header: String,
        val footer: String,
        val releases: List<ParsedRelease>,
    )

    private data class ParsedRelease(
        val heading: String,
        val version: String,
        val lines: MutableList<String>,
        val entries: MutableList<ChangelogEntry> = mutableListOf(),
    )

    private data class ChangelogEntry(
        val id: String,
        val releaseVersion: String,
        val section: String?,
        val text: String,
        val prefix: String?,
    )
}
