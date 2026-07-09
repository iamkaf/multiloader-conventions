package com.iamkaf.multiloader.root

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@DisableCachingByDefault(because = "Forgix does not guarantee reproducible merged ZIP output")
abstract class ForgixHorizontalMergeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath
    abstract val mergerClasspath: ConfigurableFileCollection

    @get:Input
    abstract val enabledLoaders: ListProperty<String>

    @get:Input
    abstract val minecraftVersion: org.gradle.api.provider.Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fabricJar: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val forgeJar: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val neoForgeJar: RegularFileProperty

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:LocalState
    abstract val workingDirectory: DirectoryProperty

    @TaskAction
    fun merge() {
        val loaders = enabledLoaders.get()
        require(loaders.size >= 2) { "Horizontal merge requires at least two loader jars" }

        val workDir = workingDirectory.get().asFile
        workDir.deleteRecursively()
        Files.createDirectories(workDir.toPath())

        val output = archiveFile.get().asFile
        Files.createDirectories(output.parentFile.toPath())
        Files.deleteIfExists(output.toPath())

        val disposableInputs = loaders.associateWith { loader ->
            val source = inputFor(loader).asFile
            require(source.isFile) { "Missing $loader input jar for ${minecraftVersion.get()}: $source" }
            val disposable = workDir.resolve("$loader.jar")
            Files.copy(source.toPath(), disposable.toPath(), StandardCopyOption.REPLACE_EXISTING)
            disposable
        }

        val arguments = mutableListOf("mergeJars", "--output", output.absolutePath)
        disposableInputs.forEach { (loader, input) ->
            arguments += "--$loader"
            arguments += input.absolutePath
        }

        logger.lifecycle(
            "[Horizontal Merge] Merging Minecraft ${minecraftVersion.get()} loaders=${loaders.joinToString(",")} -> $output",
        )
        execOperations.javaexec {
            classpath(mergerClasspath)
            mainClass.set(FORGIX_MAIN_CLASS)
            args(arguments)
        }.assertNormalExitValue()
    }

    private fun inputFor(loader: String): RegularFile =
        when (loader) {
            "fabric" -> fabricJar.get()
            "forge" -> forgeJar.get()
            "neoforge" -> neoForgeJar.get()
            else -> error("Unknown horizontal merge loader '$loader'")
        }

    companion object {
        const val FORGIX_MAIN_CLASS = "io.github.pacifistmc.forgix.Forgix"
    }
}
