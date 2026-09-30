package com.iamkaf.multiloader.support.adapters

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.jvm.tasks.Jar

/**
 * Nests dependencies in Forge mod jars. ForgeGradle has no jar-in-jar support, but Forge still loads the jars
 * listed in `META-INF/jarjar/metadata.json`.
 */
object ForgeJarJarAdapter {
    private const val CONFIGURATION = "multiloaderForgeJarJar"

    /** Returns the configuration whose dependencies are nested, creating it and the jar wiring on first use. */
    fun configuration(project: Project): String {
        if (project.configurations.findByName(CONFIGURATION) != null) return CONFIGURATION

        val bundled = project.configurations.create(CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
        }
        val artifacts = bundled.incoming.artifacts.resolvedArtifacts
        val output = project.layout.buildDirectory.dir("generated/multiloader/forgeJarJar")
        val stage = project.tasks.register("stageForgeJarJar") {
            inputs.files(bundled)
            outputs.dir(output)
            doLast {
                val root = output.get().asFile
                root.deleteRecursively()
                val entries = artifacts.get().map { artifact ->
                    val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                        ?: error("Forge jar-in-jar only nests module dependencies, not ${artifact.id}")
                    val path = "META-INF/jarjar/${artifact.file.name}"
                    artifact.file.copyTo(root.resolve(path))
                    """{"identifier":{"group":"${id.group}","artifact":"${id.module}"},""" +
                        """"version":{"range":"[${id.version},)","artifactVersion":"${id.version}"},""" +
                        """"path":"$path","isObfuscated":false}"""
                }
                root.resolve("META-INF/jarjar/metadata.json").writeText("""{"jars":[${entries.joinToString(",")}]}""")
            }
        }
        project.tasks.named("jar", Jar::class.java) { from(stage) }
        return CONFIGURATION
    }
}
