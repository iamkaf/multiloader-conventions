package com.iamkaf.multiloader.paper

import com.iamkaf.multiloader.support.ConsumerDslPolicy
import com.iamkaf.multiloader.support.RepositoryPolicy
import com.iamkaf.multiloader.support.VersionPolicy
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

/**
 * One Paper plugin jar per release. It compiles against the Paper API of `project.minecraft`, the oldest
 * supported line, and `plugin.minecraft-versions` lists every line it is expected to run on.
 */
class MultiloaderPaperPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("com.iamkaf.multiloader.paper must be applied to the root project of a plugin repository.")
        }
        ConsumerDslPolicy.requireKotlinDsl(project)
        project.pluginManager.apply(JavaPlugin::class.java)

        project.group = required(project, "project.group")
        project.version = required(project, "project.version")
        val floor = required(project, "project.minecraft")
        project.extensions.configure(JavaPluginExtension::class.java) {
            toolchain.languageVersion.set(JavaLanguageVersion.of(required(project, "project.java").toInt()))
        }

        RepositoryPolicy.configureWorkspaceRepositories(project.repositories)
        project.repositories.mavenCentral()
        project.repositories.maven {
            name = "PaperMC"
            url = project.uri("https://repo.papermc.io/repository/maven-public/")
        }

        val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
        val libs = catalogs.find("libs").orElseThrow {
            GradleException("No libs catalog. Apply com.iamkaf.multiloader.settings and set project.minecraft.")
        }
        val paperApi = libs.findLibrary("paper-api").orElseThrow {
            GradleException("The Minecraft $floor catalog has no paper-api entry, so Paper cannot be targeted there.")
        }
        project.dependencies.addProvider(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, paperApi)

        val version = project.version.toString()
        project.tasks.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, ProcessResources::class.java) {
            inputs.property("version", version)
            filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
                expand(mapOf("version" to version))
            }
        }

        registerRunServer(project, floor, libs, catalogs)
    }

    private fun registerRunServer(project: Project, floor: String, libs: VersionCatalog, catalogs: VersionCatalogsExtension) {
        val minecraft = project.providers.gradleProperty("paper.minecraft").getOrElse(floor)
        val catalog by lazy { catalogFor(minecraft, floor, libs, catalogs) }

        val download = project.tasks.register("downloadPaperServer", DownloadPaperServerTask::class.java) {
            group = "paper"
            description = "Downloads the pinned Paper server for Minecraft $minecraft."
            val build = pin(catalog, minecraft, "paper-build")
            minecraftVersion.set(minecraft)
            this.build.set(build)
            sha256.set(pin(catalog, minecraft, "paper-sha256"))
            apiBaseUrl.convention("https://fill.papermc.io")
            serverJar.set(File(project.gradle.gradleUserHomeDir, "caches/multiloader-paper/paper-$minecraft-$build.jar"))
        }

        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        val jar = project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java)
        project.tasks.register("runServer", JavaExec::class.java) {
            group = "paper"
            description = "Runs this plugin on the pinned Paper server for Minecraft $minecraft. Pick a line with -Ppaper.minecraft."
            val runDir = project.layout.projectDirectory.dir("run/$minecraft").asFile
            val pluginJar = jar.flatMap { it.archiveFile }
            val baseName = jar.flatMap { it.archiveBaseName }
            inputs.file(pluginJar)
            classpath(download.flatMap { it.serverJar })
            mainClass.set("io.papermc.paperclip.Main")
            workingDir(runDir)
            args("--nogui")
            standardInput = System.`in`
            javaLauncher.set(toolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(pin(catalog, minecraft, "java")))
            })
            doFirst {
                // Local development runs accept the Minecraft EULA.
                runDir.mkdirs()
                File(runDir, "eula.txt").apply { if (!exists()) writeText("eula=true\n") }
                val plugins = File(runDir, "plugins").apply { mkdirs() }
                plugins.listFiles { file -> file.name.startsWith("${baseName.get()}-") && file.extension == "jar" }
                    ?.forEach(File::delete)
                pluginJar.get().asFile.copyTo(File(plugins, pluginJar.get().asFile.name), overwrite = true)
            }
        }
    }

    private fun catalogFor(minecraft: String, floor: String, libs: VersionCatalog, catalogs: VersionCatalogsExtension): VersionCatalog =
        if (minecraft == floor) {
            libs
        } else {
            catalogs.find(VersionPolicy.catalogName(minecraft)).orElseThrow {
                GradleException("Minecraft $minecraft is not listed in plugin.minecraft-versions, so its catalog is not loaded.")
            }
        }

    private fun pin(catalog: VersionCatalog, minecraft: String, alias: String): String =
        catalog.findVersion(alias).orElseThrow {
            GradleException("The Minecraft $minecraft catalog has no $alias entry.")
        }.requiredVersion

    private fun required(project: Project, name: String): String =
        project.providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)
            ?: throw GradleException("Missing required gradle.properties entry: $name")
}
