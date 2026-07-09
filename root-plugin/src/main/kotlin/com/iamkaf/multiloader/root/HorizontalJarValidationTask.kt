package com.iamkaf.multiloader.root

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@DisableCachingByDefault(because = "The human-readable report records a local artifact path")
abstract class HorizontalJarValidationTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:Input
    abstract val enabledLoaders: ListProperty<String>

    @get:Input
    abstract val modId: Property<String>

    @get:Input
    abstract val tier: Property<HorizontalMergeTier>

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
    abstract val validationReport: RegularFileProperty

    @TaskAction
    fun validateJar() {
        val sources = enabledLoaders.get().associateWith { loader -> inputFor(loader).asFile }
        HorizontalJarValidator.validate(archiveFile.get().asFile, sources, modId.get(), tier.get())

        val report = validationReport.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            "validated=${archiveFile.get().asFile.absolutePath}\n" +
                "loaders=${enabledLoaders.get().joinToString(",")}\n" +
                "tier=${tier.get()}\n",
        )
        logger.lifecycle("[Horizontal Merge] Validated ${archiveFile.get().asFile}")
    }

    private fun inputFor(loader: String): RegularFile =
        when (loader) {
            "fabric" -> fabricJar.get()
            "forge" -> forgeJar.get()
            "neoforge" -> neoForgeJar.get()
            else -> error("Unknown horizontal merge loader '$loader'")
        }
}
