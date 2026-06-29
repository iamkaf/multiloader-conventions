package com.iamkaf.multiloader.root

import org.gradle.api.Project

data class TeaKitNode(
    val name: String,
    val loader: String?,
    val minecraft: String?,
)

object TeaKitNodeReader {
    fun read(project: Project): List<TeaKitNode> {
        val file = project.file("teakit.toml")
        if (!file.isFile) return emptyList()

        val nodes = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        file.forEachLine(Charsets.UTF_8) { line ->
            val section = Regex("""^\s*\[nodes\."([^"]+)"]\s*$""").find(line)
            if (section != null) {
                current = linkedMapOf("name" to section.groupValues[1])
                nodes.add(current!!)
                return@forEachLine
            }

            if (Regex("""^\s*\[.*]\s*$""").matches(line)) {
                current = null
                return@forEachLine
            }

            val active = current ?: return@forEachLine
            val property = Regex("""^\s*(loader|minecraft)\s*=\s*"([^"]+)"\s*$""").find(line)
            if (property != null) {
                active[property.groupValues[1]] = property.groupValues[2]
            }
        }

        return nodes
            .filter { it["loader"] != null || it["minecraft"] != null }
            .map { node ->
                TeaKitNode(
                    name = node.getValue("name"),
                    loader = node["loader"],
                    minecraft = node["minecraft"],
                )
            }
    }
}
