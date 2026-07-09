package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.LoaderDependencyPolicy
import com.iamkaf.multiloader.support.LoaderId
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import com.iamkaf.multiloader.support.ProjectIdentity
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
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
            languageVersion.set(JavaLanguageVersion.of(if (minecraftVersion in legacyJava16RunVersions) 16 else 17))
        }

        val lwjglNativesDir = project.layout.buildDirectory.dir("lwjgl-natives")
        val runModsDir = project.layout.projectDirectory.dir("run/mods")
        if (useTeaKit) {
            addLegacyForgeTeaKitRuntime(project, context, catalog)
        }
        val runtimeClasspath = mainRuntimeClasspath(project)
        val patchBootstrap = registerPatchLegacyBootstrapLauncher(project, minecraftVersion)
        val patchSecureJarHandler = registerPatchLegacySecureJarHandler(project, minecraftVersion)
        val patchSlimeMetadata = registerPatchLegacySlimeMetadata(project, minecraftVersion, forgeArtifactVersion)
        val extractNatives = registerExtractLwjglNatives(project, lwjglNativesDir)
        val stageProjectJar = registerStageProjectJar(project, identity, minecraftVersion, runModsDir)
        val stageDependencyMods =
            registerStageDependencyMods(project, context, catalog, minecraftVersion, runModsDir, runtimeClasspath)
        val stageTeaKit = registerStageTeaKit(
            project,
            context,
            catalog,
            minecraftVersion,
            runModsDir,
            useTeaKit,
            runtimeClasspath,
        )
        val clearSlimeCache = registerClearSlimeLauncherCache(project, forgeArtifactVersion, runModsDir)

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
                jvmArgs("--add-opens", "java.base/java.lang.invoke=cpw.mods.securejarhandler")
                doFirst {
                    patchLegacyForgeVmArgsFile(
                        project = project,
                        runTaskName = name,
                        ignoreList = ignoreList(context, catalog, useTeaKit),
                    )
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

    private val legacyJava16RunVersions = setOf("1.17.1")

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
                val source = materializeLegacyForgeMetadata(project, forgeArtifactVersion)
                val backup = File(source.parentFile, "${source.name}.multiloader-backup")
                if (!backup.exists()) {
                    Files.copy(source.toPath(), backup.toPath())
                }
                rewriteZipEntry(source, "launcher/runs.json") { bytes ->
                    patchLegacyForgeRunsJson(bytes)
                        .replace("securejarhandler-0.9.44.jar", "securejarhandler-0.9.61.jar")
                        .replace("securejarhandler-0.9.54.jar", "securejarhandler-0.9.61.jar")
                        .replace(",mixin,gson", ",mixin")
                        .replace(",gson,mixin", ",mixin")
                        .toByteArray(StandardCharsets.UTF_8)
                }
            }
        }

    private fun materializeLegacyForgeMetadata(project: Project, forgeArtifactVersion: String): File {
        val target = legacyForgeMetadataZip(project, forgeArtifactVersion)
        if (target.isFile) return target

        val userdev = resolveDetachedJar(
            project,
            "net.minecraftforge:forge:$forgeArtifactVersion:userdev",
            "forge-$forgeArtifactVersion-userdev.jar",
        )
        val runsJson = legacyForgeRunsJson(userdev)
        target.parentFile.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("launcher/runs.json"))
            zip.write(runsJson)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("version.properties"))
            zip.write("forgeVersion=$forgeArtifactVersion\n".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return target
    }

    private fun legacyForgeMetadataZip(project: Project, forgeArtifactVersion: String): File =
        project.rootProject.file(
            ".gradle/mavenizer/repo/net/minecraftforge/forge/$forgeArtifactVersion/forge-$forgeArtifactVersion-metadata.zip",
        )

    private fun legacyForgeRunsJson(userdev: File): ByteArray =
        ZipFile(userdev).use { zip ->
            val entry = zip.getEntry("config.json")
                ?: throw GradleException("Missing config.json in legacy Forge userdev jar $userdev")
            val config = zip.getInputStream(entry).use { input ->
                JsonSlurper().parse(input) as? Map<*, *>
                    ?: throw GradleException("Could not parse config.json in legacy Forge userdev jar $userdev")
            }
            val runs = config["runs"]
                ?: throw GradleException("Missing runs metadata in legacy Forge userdev jar $userdev")
            JsonOutput.prettyPrint(JsonOutput.toJson(runs)).toByteArray(StandardCharsets.UTF_8)
        }

    @Suppress("UNCHECKED_CAST")
    private fun patchLegacyForgeRunsJson(bytes: ByteArray): String {
        val runs = JsonSlurper().parseText(String(bytes, StandardCharsets.UTF_8)) as? MutableMap<String, Any?>
            ?: throw GradleException("Could not parse legacy Forge launcher/runs.json")
        runs.values.forEach { run ->
            val runConfig = run as? MutableMap<String, Any?> ?: return@forEach
            val props = runConfig.getOrPut("props") { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
            if (props.containsKey("legacyClassPath") && !props.containsKey("legacyClassPath.file")) {
                props.remove("legacyClassPath")
                props["legacyClassPath.file"] = "{minecraft_classpath_file}"
            }
            val jvmArgs = runConfig.getOrPut("jvmArgs") { mutableListOf<String>() } as MutableList<Any?>
            addJvmArgPair(jvmArgs, "--add-opens", "java.base/java.lang.invoke=cpw.mods.securejarhandler")
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(runs))
    }

    private fun addJvmArgPair(jvmArgs: MutableList<Any?>, flag: String, value: String) {
        if (jvmArgs.contains(value)) return
        jvmArgs += flag
        jvmArgs += value
    }

    private fun legacyClasspathFile(project: Project, runTaskName: String): File {
        val runName = if (runTaskName == "runServer") "server" else "client"
        val file = project.layout.buildDirectory.file("moddev/${runName}LegacyClasspath.txt").get().asFile
        if (!file.isFile) {
            throw GradleException("Missing generated LegacyForge classpath file at $file")
        }
        return file
    }

    private fun patchLegacyForgeVmArgsFile(project: Project, runTaskName: String, ignoreList: String) {
        val runName = if (runTaskName == "runServer") "server" else "client"
        val vmArgsFile = project.layout.buildDirectory.file("moddev/${runName}RunVmArgs.txt").get().asFile
        if (!vmArgsFile.isFile) {
            throw GradleException("Missing generated LegacyForge VM args file at $vmArgsFile")
        }

        val legacyClasspathFile = legacyClasspathFile(project, runTaskName)
        val patchedLines = vmArgsFile.readLines()
            .filterNot { line ->
                line.startsWith("-DlegacyClassPath=") ||
                    line.startsWith("-DlegacyClassPath.file=") ||
                    line.startsWith("-DignoreList=")
            }
            .toMutableList()
        patchedLines += "-DlegacyClassPath.file=${legacyClasspathFile.absolutePath}"
        patchedLines += "-DignoreList=$ignoreList"
        vmArgsFile.writeText(patchedLines.joinToString(System.lineSeparator()) + System.lineSeparator())
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
        runtimeClasspath: FileCollection,
    ): TaskProvider<*> =
        project.tasks.register("stageLegacyForgeDependencyMods") {
            group = "minecraft"
            description = "Stages remapped dependency mod jars into run/mods for old LegacyForge $minecraftVersion client runs."
            onlyIf { minecraftVersion in legacyRuntimeVersions }
            outputs.dir(runModsDir)
            inputs.files(runtimeClasspath)
            doLast {
                val modsDir = runModsDir.asFile
                modsDir.mkdirs()
                project.delete(project.fileTree(modsDir) {
                    include("amber-forge-*.jar")
                    include("konfig-forge-*.jar")
                })
                val runtimeFiles = runtimeClasspath.files
                listOf("amber", "konfig").forEach { alias ->
                    LoaderDependencyPolicy.catalogModuleVersion(context, catalog, alias)
                        ?: throw GradleException("Missing $alias version for legacy Forge $minecraftVersion")
                    val source = runtimeFiles.requiredForgeModJar(alias)
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
        runtimeClasspath: FileCollection,
    ): TaskProvider<*> =
        project.tasks.register("stageLegacyForgeTeaKitMod") {
            group = "minecraft"
            description = "Stages remapped TeaKit into run/mods for old LegacyForge $minecraftVersion client runs."
            onlyIf { useTeaKit }
            outputs.dir(runModsDir)
            inputs.files(runtimeClasspath)
            doLast {
                LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "teakit")
                    ?: throw GradleException("Missing teakit version for legacy Forge $minecraftVersion")
                val source = runtimeClasspath.files.requiredForgeModJar("teakit")
                val modsDir = runModsDir.asFile
                modsDir.mkdirs()
                project.delete(project.fileTree(modsDir) { include("teakit-forge-*.jar") })
                val target = File(modsDir, source.name)
                copyJarWithoutMixinManifest(source, target)
            }
        }

    private fun mainRuntimeClasspath(project: Project): FileCollection =
        project.extensions.getByType(SourceSetContainer::class.java)
            .getByName("main")
            .runtimeClasspath

    private fun addLegacyForgeTeaKitRuntime(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
    ) {
        val dependency = context.libraryOrNull(catalog, "teakit-${LoaderId.FORGE.id}") ?: return
        project.dependencies.add("modRuntimeOnly", dependency)
    }

    private fun Set<File>.requiredForgeModJar(alias: String): File {
        val matches = filter { file -> file.name.startsWith("$alias-forge-") && file.name.endsWith(".jar") }
        val transformed = matches.filter { file ->
            file.absolutePath.contains("${File.separator}transforms${File.separator}")
        }
        return transformed.singleOrNull()
            ?: matches.singleOrNull()
            ?: throw GradleException("Missing remapped $alias-forge jar in legacy Forge runtime classpath: ${map { it.name }.sorted()}")
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
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        useTeaKit: Boolean,
    ): String {
        val amberVersion = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "amber")
        val konfigVersion = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "konfig")
        val teaKitVersion = LoaderDependencyPolicy.catalogModuleVersion(context, catalog, "teakit")
        return listOfNotNull(
            "bootstraplauncher",
            "securejarhandler",
            "asm-commons",
            "asm-util",
            "asm-analysis",
            "asm-tree",
            "asm",
            "JarJarFileSystems",
            "client-extra",
            "fmlcore",
            "javafmllanguage",
            "lowcodelanguage",
            "mclanguage",
            "forge-",
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
