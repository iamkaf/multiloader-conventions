package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.LoaderDependencyPolicy
import com.iamkaf.multiloader.support.LoaderId
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.ProjectIdentity
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object LegacyForgeRuntimeAdapter {
    private val legacyRuntimeVersions = LoaderDependencyPolicy.legacyForgeRuntimeModVersions
    private val legacySlimePatchVersions = setOf("1.17.1", "1.18", "1.18.1")
    private val legacyJarRunVersions = setOf("1.17.1", "1.18")

    fun configure(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
        minecraftVersion: String,
    ) {
        if (minecraftVersion !in legacyRuntimeVersions) return

        val forgeVersion = context.versionOrNull(catalog, "forge")
            ?: throw GradleException("Missing Forge version for ${project.path}")
        val forgeArtifactVersion = ForgeGradleAdapter.artifactVersion(minecraftVersion, forgeVersion)
        val useTeaKit = shouldUseTeaKit(project, context, catalog, identity)
        val runLauncher = project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(if (minecraftVersion == "1.17.1") 16 else 17))
        }

        val lwjglNativesDir = project.layout.buildDirectory.dir("lwjgl-natives")
        val runModsDir = project.layout.projectDirectory.dir("run/mods")
        val patchBootstrap = registerPatchLegacyBootstrapLauncher(project, minecraftVersion)
        val patchSecureJarHandler = registerPatchLegacySecureJarHandler(project, minecraftVersion)
        val patchSlimeMetadata = registerPatchLegacySlimeMetadata(project, minecraftVersion, forgeArtifactVersion)
        val extractNatives = registerExtractLwjglNatives(project, lwjglNativesDir)
        val stageProjectJar = registerStageProjectJar(project, identity, minecraftVersion, runModsDir)
        val stageDependencyMods = registerStageDependencyMods(project, context, catalog, minecraftVersion, runModsDir)
        val stageTeaKit = registerStageTeaKit(project, context, catalog, minecraftVersion, runModsDir, useTeaKit)
        val clearSlimeCache = registerClearSlimeLauncherCache(project, forgeArtifactVersion, runModsDir)
        val filteredRuntimeClasspath = project.providers.provider {
            project.extensions.getByType(SourceSetContainer::class.java)
                .getByName("main")
                .runtimeClasspath
                .files
                .map { it.absolutePath }
                .filterNot { path ->
                    val fileName = File(path).name
                    fileName.startsWith("securejarhandler-") ||
                        fileName.startsWith("bootstraplauncher-") ||
                        fileName.startsWith("amber-forge-") ||
                        fileName.startsWith("konfig-forge-") ||
                        fileName.startsWith("teakit-forge-")
                }
                .joinToString(File.pathSeparator)
        }

        project.tasks.withType(JavaExec::class.java).matching {
            it.name == "runClient" || it.name == "runServer"
        }.configureEach {
            dependsOn(patchBootstrap)
            javaLauncher.set(runLauncher)
            setArgs(argsWithoutMixinConfig(args ?: emptyList()))

            if (minecraftVersion in legacySlimePatchVersions) {
                dependsOn(patchSecureJarHandler)
                dependsOn(patchSlimeMetadata)
                dependsOn(clearSlimeCache)
                doFirst {
                    systemProperty("legacyClassPath", filteredRuntimeClasspath.get())
                    systemProperty("ignoreList", ignoreList(minecraftVersion, forgeArtifactVersion, context, catalog, useTeaKit))
                }
            }

            if (minecraftVersion in legacyJarRunVersions) {
                doFirst { environment("MOD_CLASSES", "") }
            }
        }

        project.tasks.withType(JavaExec::class.java).matching { it.name == "runClient" }.configureEach {
            dependsOn(extractNatives)
            dependsOn(stageDependencyMods)
            if (minecraftVersion in legacyJarRunVersions) {
                dependsOn(stageProjectJar)
            }
            if (useTeaKit) {
                dependsOn(stageTeaKit)
            }
            systemProperty("java.library.path", lwjglNativesDir.get().asFile.absolutePath)
            systemProperty("org.lwjgl.librarypath", lwjglNativesDir.get().asFile.absolutePath)
        }
    }

    private fun registerPatchLegacyBootstrapLauncher(project: Project, minecraftVersion: String): TaskProvider<*> {
        val legacyVersion = when (minecraftVersion) {
            "1.18.2" -> "1.0.0"
            "1.18", "1.18.1" -> "0.1.17"
            else -> "0.1.15"
        }
        return project.tasks.register("patchLegacyForgeBootstrapLauncher") {
            group = "minecraft"
            description = "Patches old LegacyForge bootstraplauncher cache for $minecraftVersion runs."
            doLast {
                val source = resolveDetachedJar(project, "cpw.mods:bootstraplauncher:1.1.2", "bootstraplauncher-1.1.2.jar")
                val target = resolveCachedJar(project, "cpw.mods", "bootstraplauncher", legacyVersion)
                copyWithBackupIfDifferent(source, target, "multiloader-backup")
            }
        }
    }

    private fun registerPatchLegacySecureJarHandler(project: Project, minecraftVersion: String): TaskProvider<*> {
        val legacyVersion = if (minecraftVersion == "1.18" || minecraftVersion == "1.18.1") "0.9.54" else "0.9.44"
        return project.tasks.register("patchLegacyForgeSecureJarHandler") {
            group = "minecraft"
            description = "Patches old LegacyForge securejarhandler cache for $minecraftVersion runs."
            onlyIf { minecraftVersion in legacySlimePatchVersions }
            doLast {
                val source = resolveDetachedJar(project, "cpw.mods:securejarhandler:0.9.61", "securejarhandler-0.9.61.jar")
                val target = resolveCachedJar(project, "cpw.mods", "securejarhandler", legacyVersion)
                copyWithBackupIfDifferent(source, target, "multiloader-backup")
            }
        }
    }

    private fun registerPatchLegacySlimeMetadata(
        project: Project,
        minecraftVersion: String,
        forgeArtifactVersion: String,
    ): TaskProvider<*> =
        project.tasks.register("patchLegacyForgeSlimeMetadata") {
            group = "minecraft"
            description = "Patches old LegacyForge slime launcher metadata for $minecraftVersion runs."
            onlyIf { minecraftVersion in legacySlimePatchVersions }
            doLast {
                val source = project.rootProject.file(
                    ".gradle/mavenizer/repo/net/minecraftforge/forge/$forgeArtifactVersion/forge-$forgeArtifactVersion-metadata.zip",
                )
                if (!source.isFile) {
                    throw GradleException("Missing legacy Forge metadata zip at $source")
                }
                val backup = File(source.parentFile, "${source.name}.multiloader-backup")
                if (!backup.exists()) {
                    Files.copy(source.toPath(), backup.toPath())
                }
                rewriteZipEntry(source, "launcher/runs.json") { bytes ->
                    String(bytes, StandardCharsets.UTF_8)
                        .replace("securejarhandler-0.9.44.jar", "securejarhandler-0.9.61.jar")
                        .replace("securejarhandler-0.9.54.jar", "securejarhandler-0.9.61.jar")
                        .replace(",mixin,gson", ",mixin")
                        .replace(",gson,mixin", ",mixin")
                        .toByteArray(StandardCharsets.UTF_8)
                }
            }
        }

    private fun registerExtractLwjglNatives(
        project: Project,
        lwjglNativesDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    ): TaskProvider<Sync> =
        project.tasks.register("extractLegacyForgeLwjglNatives", Sync::class.java) {
            into(lwjglNativesDir)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from({
                lwjglNativeJars(project).map { project.zipTree(it) }
            }) {
                exclude("META-INF/**", "**/*.sha1", "**/*.git")
            }
        }

    private fun registerStageProjectJar(
        project: Project,
        identity: ProjectIdentity,
        minecraftVersion: String,
        runModsDir: org.gradle.api.file.Directory,
    ): TaskProvider<*> =
        project.tasks.register("stageLegacyForgeProjectJar") {
            group = "minecraft"
            description = "Stages this mod jar into run/mods for old LegacyForge $minecraftVersion client runs."
            onlyIf { minecraftVersion in legacyJarRunVersions }
            dependsOn(project.tasks.named("jar"))
            outputs.dir(runModsDir)
            doLast {
                val source = project.tasks.named("jar", Jar::class.java).get().archiveFile.get().asFile
                val modsDir = runModsDir.asFile
                modsDir.mkdirs()
                project.delete(project.fileTree(modsDir) { include("${identity.archiveName}-*.jar") })
                Files.copy(source.toPath(), File(modsDir, source.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

    private fun registerStageDependencyMods(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
        runModsDir: org.gradle.api.file.Directory,
    ): TaskProvider<*> =
        project.tasks.register("stageLegacyForgeDependencyMods") {
            group = "minecraft"
            description = "Stages dependency mod jars into run/mods for old LegacyForge $minecraftVersion client runs."
            onlyIf { minecraftVersion in legacyRuntimeVersions }
            outputs.dir(runModsDir)
            doLast {
                val modsDir = runModsDir.asFile
                modsDir.mkdirs()
                project.delete(project.fileTree(modsDir) {
                    include("amber-forge-*.jar")
                    include("konfig-forge-*.jar")
                })
                listOf("amber", "konfig").forEach { alias ->
                    val version = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, alias)
                        ?: throw GradleException("Missing $alias version for legacy Forge $minecraftVersion")
                    val source = resolveDetachedJar(
                        project,
                        "com.iamkaf.$alias:$alias-forge:$version",
                        "$alias-forge-",
                    )
                    Files.copy(source.toPath(), File(modsDir, source.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

    private fun registerStageTeaKit(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
        runModsDir: org.gradle.api.file.Directory,
        useTeaKit: Boolean,
    ): TaskProvider<*> =
        project.tasks.register("stageLegacyForgeTeaKitMod") {
            group = "minecraft"
            description = "Stages TeaKit into run/mods for old LegacyForge $minecraftVersion client runs."
            onlyIf { useTeaKit }
            outputs.dir(runModsDir)
            doLast {
                val version = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "teakit")
                    ?: throw GradleException("Missing teakit version for legacy Forge $minecraftVersion")
                val source = resolveDetachedJar(project, "com.iamkaf.teakit:teakit-forge:$version", "teakit-forge-")
                val modsDir = runModsDir.asFile
                modsDir.mkdirs()
                project.delete(project.fileTree(modsDir) { include("teakit-forge-*.jar") })
                val target = File(modsDir, source.name)
                copyJarWithoutMixinManifest(source, target)
            }
        }

    private fun registerClearSlimeLauncherCache(
        project: Project,
        forgeArtifactVersion: String,
        runModsDir: org.gradle.api.file.Directory,
    ): TaskProvider<*> =
        project.tasks.register("clearLegacyForgeSlimeLauncherCache") {
            group = "minecraft"
            description = "Clears old LegacyForge slime launcher caches before patched runs."
            doLast {
                project.delete(
                    File(
                        project.gradle.gradleUserHomeDir,
                        "caches/minecraftforge/forgegradle/slime-launcher/cache/net/minecraftforge/forge/$forgeArtifactVersion",
                    ),
                )
                project.delete(runModsDir)
            }
        }

    private fun shouldUseTeaKit(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        identity: ProjectIdentity,
    ): Boolean {
        if (identity.modId == "teakit") return false
        val version = context.versionOrNull(catalog, "teakit")
        if (version.isNullOrBlank() || version == "null") return false
        if (context.libraryOrNull(catalog, "teakit-${LoaderId.FORGE.id}") == null) return false
        return project.providers.systemProperty("${identity.modId}.withTeaKit")
            .orElse(project.providers.gradleProperty("${identity.modId}.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get()
    }

    private fun argsWithoutMixinConfig(args: List<String>): List<String> {
        val filtered = mutableListOf<String>()
        var skipNext = false
        args.forEach { arg ->
            if (skipNext) {
                skipNext = false
            } else if (arg == "--mixin.config") {
                skipNext = true
            } else {
                filtered += arg
            }
        }
        return filtered
    }

    private fun ignoreList(
        minecraftVersion: String,
        forgeArtifactVersion: String,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        useTeaKit: Boolean,
    ): String {
        val bootstrapVersion = when (minecraftVersion) {
            "1.18.2" -> "1.0.0"
            "1.18", "1.18.1" -> "0.1.17"
            else -> "0.1.15"
        }
        val amberVersion = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "amber")
        val konfigVersion = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "konfig")
        val teaKitVersion = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "teakit")
        return listOfNotNull(
            "bootstraplauncher-$bootstrapVersion.jar",
            "securejarhandler-0.9.61.jar",
            "asm-commons-9.1.jar",
            "asm-util-9.1.jar",
            "asm-analysis-9.1.jar",
            "asm-tree-9.1.jar",
            "asm-9.1.jar",
            "client-extra",
            "fmlcore",
            "javafmllanguage",
            "mclanguage",
            "forge-$forgeArtifactVersion",
            forgeArtifactVersion,
            amberVersion?.let { "amber-forge-$it.jar" },
            konfigVersion?.let { "konfig-forge-$it.jar" },
            if (useTeaKit) teaKitVersion?.let { "teakit-forge-$it.jar" } else null,
        ).joinToString(",")
    }

    private fun resolveDetachedJar(project: Project, coordinate: String, fileNameOrPrefix: String): File =
        project.configurations.detachedConfiguration(project.dependencies.create(coordinate))
            .resolve()
            .firstOrNull {
                if (fileNameOrPrefix.endsWith(".jar")) it.name == fileNameOrPrefix
                else it.name.startsWith(fileNameOrPrefix) && it.name.endsWith(".jar")
            }
            ?: throw GradleException("Missing jar '$fileNameOrPrefix' from $coordinate")

    private fun resolveCachedJar(project: Project, group: String, module: String, version: String): File =
        project.fileTree(
            File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/$group/$module/$version"),
        ) {
            include("**/$module-$version.jar")
            exclude("**/*sources.jar")
        }.files.singleOrNull()
            ?: throw GradleException("Missing cached $module-$version.jar")

    private fun copyWithBackupIfDifferent(source: File, target: File, backupSuffix: String) {
        val backup = File(target.parentFile, "${target.name}.$backupSuffix")
        if (!backup.exists()) {
            Files.copy(target.toPath(), backup.toPath())
        }
        if (target.length() != source.length() || !target.readBytes().contentEquals(source.readBytes())) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun lwjglNativeJars(project: Project): Set<File> {
        val jars = linkedSetOf<File>()
        jars += project.fileTree(File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/org.lwjgl")) {
            include("**/*natives-linux.jar")
        }.files
        jars += project.fileTree(File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/com.mojang")) {
            include("**/text2speech-*-natives-linux.jar")
        }.files
        if (jars.isEmpty()) {
            throw GradleException("Missing LWJGL native jars for legacy Forge runClient")
        }
        return jars
    }

    private fun rewriteZipEntry(source: File, entryName: String, transform: (ByteArray) -> ByteArray) {
        val tempFile = File(source.parentFile, "${source.name}.multiloader-temp")
        ZipFile(source).use { zipFile ->
            if (zipFile.getEntry(entryName) == null) {
                throw GradleException("Missing $entryName in $source")
            }
            ZipOutputStream(FileOutputStream(tempFile)).use { out ->
                zipFile.entries().asSequence().forEach { entry ->
                    val newEntry = ZipEntry(entry.name)
                    newEntry.time = entry.time
                    out.putNextEntry(newEntry)
                    if (!entry.isDirectory) {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        out.write(if (entry.name == entryName) transform(bytes) else bytes)
                    }
                    out.closeEntry()
                }
            }
        }
        Files.move(tempFile.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun copyJarWithoutMixinManifest(source: File, target: File) {
        JarFile(source).use { jarFile ->
            val manifest = jarFile.manifest ?: java.util.jar.Manifest()
            manifest.mainAttributes.remove(Attributes.Name("MixinConfigs"))
            val tempFile = File(target.parentFile, "${target.name}.temp")
            JarOutputStream(FileOutputStream(tempFile), manifest).use { out ->
                jarFile.entries().asSequence().forEach { entry ->
                    if (entry.name == "META-INF/MANIFEST.MF") return@forEach
                    val newEntry = JarEntry(entry.name)
                    newEntry.time = entry.time
                    out.putNextEntry(newEntry)
                    if (!entry.isDirectory) {
                        jarFile.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
