package com.iamkaf.multiloader.forge

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecOperations
import java.io.File
import java.util.jar.JarFile
import javax.inject.Inject

/**
 * Maps the SRG member names in a Forge 1.20.2-1.20.4 mod jar built by these conventions back to Mojang names for
 * dev classpaths. Other jars pass through untouched. Mojang-named jars from before the SRG remap rename to themselves.
 */
@CacheableTransform
abstract class SrgToMojangTransform : TransformAction<SrgToMojangTransform.Parameters> {
    interface Parameters : TransformParameters {
        @get:Classpath
        val renamerClasspath: ConfigurableFileCollection

        /** The official-to-SRG mappings, applied in reverse. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        val mappings: ConfigurableFileCollection
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        if (!isConventionModJar(jar)) {
            outputs.file(jar)
            return
        }

        // Keep the file name: dev tooling finds workspace libraries on the classpath by name.
        val output = outputs.file(jar.name)
        execOperations.javaexec {
            classpath(parameters.renamerClasspath)
            mainClass.set("net.minecraftforge.renamer.Main")
            args(
                "--input", jar.absolutePath,
                "--output", output.absolutePath,
                "--map", parameters.mappings.singleFile.absolutePath,
                "--reverse",
                // Rename by SRG name alone, so the transform needs no Minecraft jar for inheritance.
                "--naive-srg",
                "--access-transformers",
            )
        }
    }

    private fun isConventionModJar(file: File): Boolean =
        file.isFile && file.name.endsWith(".jar") && JarFile(file).use { jar ->
            jar.manifest?.mainAttributes?.getValue("Built-By") == "multiloader-conventions" &&
                jar.getEntry("META-INF/mods.toml") != null
        }
}
