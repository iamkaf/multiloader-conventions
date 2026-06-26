package com.iamkaf.multiloader.support

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources

object ConventionSupport {
    @JvmField
    val LOADER_PROJECTS: List<String> = listOf("fabric", "forge", "neoforge")

    @JvmStatic
    fun configureCommonProject(project: Project) {
        project.pluginManager.apply(JavaLibraryPlugin::class.java)
        project.pluginManager.apply("maven-publish")

        configureRepositories(project)
        configureCoordinates(project)
        configureArchiveNaming(project)
        configureJava(project)
        configureResourceMetadata(project)
        configureManifests(project)
        configureLicensePackaging(project)
        configureCapabilities(project)
        configurePublishing(project)
        configureCommonArtifacts(project)
    }

    @JvmStatic
    fun configureLoaderBridge(project: Project) {
        if (project.name !in LOADER_PROJECTS) return
        val commonProject = project.rootProject.findProject(":common") ?: return

        val commonJava = project.configurations.maybeCreate("commonJava")
        commonJava.isCanBeResolved = true
        commonJava.isCanBeConsumed = false

        val commonResources = project.configurations.maybeCreate("commonResources")
        commonResources.isCanBeResolved = true
        commonResources.isCanBeConsumed = false

        val commonDependency = project.dependencies.project(mapOf("path" to ":common")) as ProjectDependency
        commonDependency.capabilities {
            requireCapability("${project.group}:${requiredProperty(project, "mod.id")}")
        }
        project.dependencies.add("compileOnly", commonDependency)
        project.dependencies.add("commonJava", project.dependencies.project(mapOf("path" to ":common", "configuration" to "commonJava")))
        project.dependencies.add("commonResources", project.dependencies.project(mapOf("path" to ":common", "configuration" to "commonResources")))

        project.tasks.named("compileJava", JavaCompile::class.java) {
            dependsOn(commonJava)
            source(commonJava)
        }

        project.tasks.named("processResources", ProcessResources::class.java) {
            dependsOn(commonResources)
            from(commonResources)
            exclude(".cache/**")
        }

        project.tasks.named("javadoc") {
            dependsOn(commonJava)
            GroovyGradleDsl.invoke(this, "source", commonJava)
        }

        project.tasks.named("sourcesJar", Jar::class.java) {
            dependsOn(commonJava)
            from(commonJava)
            dependsOn(commonResources)
            from(commonResources)
            exclude(".cache/**")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    @JvmStatic
    fun configureFabricBaseDependencies(project: Project) {
        project.dependencies.add("minecraft", requiredLibrary(project, "minecraft"))
        if (!isUnobfuscatedMinecraft(project)) {
            val loom = project.extensions.getByName("loom")
            val mappings = GroovyGradleDsl.invoke(
                loom,
                "layered",
                GroovyGradleDsl.closure { layered ->
                    GroovyGradleDsl.invoke(layered, "officialMojangMappings")
                    GroovyGradleDsl.invoke(layered, "parchment", requiredLibrary(project, "parchment"))
                },
            )
            project.dependencies.add("mappings", mappings!!)
            project.dependencies.add("modImplementation", requiredLibrary(project, "fabric-loader"))
            return
        }

        project.dependencies.add("implementation", requiredLibrary(project, "fabric-loader"))
    }

    @JvmStatic
    fun configureRepositories(project: Project) {
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        project.repositories.maven {
            name = "TerraformersMC"
            url = project.uri("https://maven.terraformersmc.com/")
            metadataSources {
                mavenPom()
                artifact()
            }
            content {
                includeGroup("com.terraformersmc")
            }
        }
        project.repositories.maven {
            name = "Nucleoid"
            url = project.uri("https://maven.nucleoid.xyz/")
            content { includeGroup("eu.pb4") }
        }
        project.repositories.maven {
            name = "Sponge"
            url = project.uri("https://repo.spongepowered.org/repository/maven-public")
        }
        project.repositories.maven {
            name = "ParchmentMC"
            url = project.uri("https://maven.parchmentmc.org/")
        }
        project.repositories.maven {
            name = "NeoForge"
            url = project.uri("https://maven.neoforged.net/releases")
        }
        project.repositories.maven {
            name = "BlameJared"
            url = project.uri("https://maven.blamejared.com")
        }
        project.repositories.maven {
            name = "Modrinth"
            url = project.uri("https://api.modrinth.com/maven")
        }
        project.repositories.maven {
            name = "Kaf Maven"
            url = project.uri("https://maven.kaf.sh")
        }
        project.repositories.maven {
            name = "Fuzs Mod Resources"
            url = project.uri("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/")
        }
    }

    @JvmStatic
    fun configureCoordinates(project: Project) {
        project.rootProject.findProperty("project.group")?.let { project.group = it.toString() }
        project.rootProject.findProperty("project.version")?.let { project.version = it.toString() }
    }

    @JvmStatic
    fun configureArchiveNaming(project: Project) {
        project.extensions.configure(BasePluginExtension::class.java) {
            archivesName.set("${requiredProperty(project, "mod.id")}-${project.name}")
        }
    }

    @JvmStatic
    fun configureJava(project: Project) {
        val javaVersion = project.findProperty("project.java")?.toString()?.toIntOrNull()
        project.extensions.configure(JavaPluginExtension::class.java) {
            withSourcesJar()
            withJavadocJar()
            if (javaVersion != null) {
                toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
            }
        }

        project.extensions.findByType(SourceSetContainer::class.java)
            ?.named("main") {
                resources.srcDir(project.file("src/main/generated"))
            }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
        }

        configureJavadoc(project)
    }

    @JvmStatic
    fun configureJavadoc(project: Project) {
        project.tasks.withType(Javadoc::class.java).configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        }
    }

    @JvmStatic
    fun configureResourceMetadata(project: Project) {
        project.tasks.withType(ProcessResources::class.java).configureEach {
            val expandProps = buildExpandProperties(project)
            val inputProps = expandProps.filterValues { it != null }
            val jsonExpandProps = expandProps.mapValues { (_, value) ->
                if (value is String) value.replace("\n", "\\n") else value
            }

            exclude(".cache/**")
            filesMatching(listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
                expand(expandProps)
            }
            filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json")) {
                expand(jsonExpandProps)
            }
            inputs.properties(inputProps)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    @JvmStatic
    fun configureManifests(project: Project) {
        project.tasks.named("jar", Jar::class.java) {
            manifest.attributes(
                mapOf(
                    "Specification-Title" to requiredProperty(project, "mod.name"),
                    "Specification-Vendor" to optionalProperty(project, "mod.authors"),
                    "Specification-Version" to project.version.toString(),
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version.toString(),
                    "Implementation-Vendor" to optionalProperty(project, "mod.authors"),
                    "Built-On-Minecraft" to versionAlias(project, "minecraft"),
                    "Built-By" to "multiloader-conventions",
                ),
            )
        }
    }

    @JvmStatic
    fun configureLicensePackaging(project: Project) {
        val licenseFile = resolveLicenseFile(project)
        if (!licenseFile.exists()) return

        listOf("jar", "sourcesJar").forEach { taskName ->
            project.tasks.named(taskName, Jar::class.java) {
                from(licenseFile) {
                    rename { "${it}_${requiredProperty(project, "mod.name")}" }
                }
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                exclude(".cache/**")
            }
        }
    }

    @JvmStatic
    fun configureCapabilities(project: Project) {
        listOf("apiElements", "runtimeElements", "sourcesElements", "javadocElements").forEach { variant ->
            project.configurations.named(variant) {
                val archivesName = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
                outgoing.capability("${project.group}:${project.name}:${project.version}")
                outgoing.capability("${project.group}:$archivesName:${project.version}")
                outgoing.capability("${project.group}:${requiredProperty(project, "mod.id")}-${project.name}-${versionAlias(project, "minecraft")}:${project.version}")
                outgoing.capability("${project.group}:${requiredProperty(project, "mod.id")}:${project.version}")
            }

            project.extensions.configure(PublishingExtension::class.java) {
                publications.withType(MavenPublication::class.java).configureEach {
                    suppressPomMetadataWarningsFor(variant)
                }
            }
        }
    }

    @JvmStatic
    fun configurePublishing(project: Project) {
        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("mavenJava", MavenPublication::class.java) {
                groupId = project.group.toString()
                artifactId = project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
                from(project.components.getByName("java"))
            }
            repositories.maven {
                name = "KafMaven"
                url = project.uri(
                    if (project.version.toString().endsWith("-SNAPSHOT")) "https://z.kaf.sh/snapshots"
                    else "https://z.kaf.sh/releases",
                )
                credentials {
                    username = System.getenv("MAVEN_PUBLISH_USERNAME")
                    password = System.getenv("MAVEN_PUBLISH_PASSWORD")
                }
            }
        }
    }

    @JvmStatic
    fun configureCommonArtifacts(project: Project) {
        if (project.name != "common") return

        val commonJava = project.configurations.maybeCreate("commonJava")
        commonJava.isCanBeResolved = false
        commonJava.isCanBeConsumed = true

        val commonResources = project.configurations.maybeCreate("commonResources")
        commonResources.isCanBeResolved = false
        commonResources.isCanBeConsumed = true

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        project.artifacts {
            add("commonJava", sourceSets.named("main").get().java.sourceDirectories.singleFile)
            sourceSets.named("main").get().resources.sourceDirectories.files.forEach { directory ->
                add("commonResources", directory)
            }
        }
    }

    @JvmStatic
    fun commonFile(project: Project, relativePath: String): java.io.File {
        val commonProject = project.rootProject.findProject(":common")
        return commonProject?.file(relativePath) ?: project.file(relativePath)
    }

    @JvmStatic
    fun collectMixinConfigs(project: Project, loaderName: String): List<String> {
        val modId = requiredProperty(project, "mod.id")
        val mixinConfigs = mutableListOf<String>()
        val commonMixin = commonFile(project, "src/main/resources/$modId.mixins.json")
        if (commonMixin.exists()) {
            mixinConfigs.add("$modId.mixins.json")
        }
        val loaderMixin = project.file("src/main/resources/$modId.$loaderName.mixins.json")
        if (loaderMixin.exists()) {
            mixinConfigs.add("$modId.$loaderName.mixins.json")
        }
        return mixinConfigs
    }

    @JvmStatic
    fun resolveLicenseFile(project: Project): java.io.File {
        val repoRootLicense = project.rootProject.file("../LICENSE")
        return if (repoRootLicense.exists()) repoRootLicense else project.rootProject.file("LICENSE")
    }

    @JvmStatic
    fun buildExpandProperties(project: Project): Map<String, Any?> =
        linkedMapOf(
            "version" to project.version.toString(),
            "group" to project.group.toString(),
            "minecraft_version" to versionAlias(project, "minecraft"),
            "minecraft_version_range" to requiredProperty(project, "mod.minecraft-range"),
            "fabric_version_range" to optionalProperty(project, "mod.fabric-range"),
            "fabric_version" to optionalVersionAlias(project, "fabric-api"),
            "fabric_loader_version" to optionalVersionAlias(project, "fabric-loader"),
            "mod_menu_version" to optionalVersionAlias(project, "modmenu"),
            "mod_name" to requiredProperty(project, "mod.name"),
            "mod_author" to optionalProperty(project, "mod.authors"),
            "mod_id" to requiredProperty(project, "mod.id"),
            "license" to optionalProperty(project, "mod.license"),
            "description" to optionalProperty(project, "mod.description"),
            "neoforge_version" to optionalVersionAlias(project, "neoforge"),
            "neoforge_loader_version_range" to optionalProperty(project, "mod.neoforge-loader-range"),
            "forge_version" to optionalVersionAlias(project, "forge"),
            "forge_loader_version_range" to optionalProperty(project, "mod.forge-loader-range"),
            "amber_version" to optionalVersionAlias(project, "amber"),
            "konfig_version" to optionalVersionAlias(project, "konfig"),
            "parchment_minecraft" to optionalVersionAlias(project, "parchment-minecraft"),
            "parchment_version" to optionalVersionAlias(project, "parchment"),
            "credits" to optionalProperty(project, "mod.credits"),
            "java_version" to requiredProperty(project, "project.java"),
        )

    @JvmStatic
    fun versionCatalog(project: Project): VersionCatalog {
        val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java)
            ?: throw IllegalStateException("Missing version catalogs for ${project.path}")
        return catalogs.named("libs")
    }

    @JvmStatic
    fun requiredLibrary(project: Project, alias: String): Any {
        val libs = versionCatalog(project)
        val library = libs.findLibrary(alias)
        if (!library.isPresent) {
            throw IllegalStateException("Missing required library alias '$alias' for ${project.path}")
        }
        return library.get().get()
    }

    @JvmStatic
    fun versionAlias(project: Project, alias: String): String {
        val value = optionalVersionAlias(project, alias)
        if (value.isNullOrBlank()) {
            throw IllegalStateException("Missing required version catalog alias '$alias' for ${project.path}")
        }
        return value
    }

    @JvmStatic
    fun optionalVersionAlias(project: Project, alias: String): String? {
        val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java) ?: return null
        val libs = catalogs.named("libs")
        val version = libs.findVersion(alias)
        if (!version.isPresent) return null

        val resolved = version.get().requiredVersion
        return resolved?.takeUnless { it.isBlank() || it == "null" }
    }

    @JvmStatic
    fun requiredProperty(project: Project, propertyName: String): String {
        val value = optionalProperty(project, propertyName)
        if (value.isNullOrBlank()) {
            throw IllegalStateException("Missing required property '$propertyName' for ${project.path}")
        }
        return value
    }

    @JvmStatic
    fun optionalProperty(project: Project, propertyName: String): String? =
        project.findProperty(propertyName)?.toString()

    @JvmStatic
    fun isUnobfuscatedMinecraft(project: Project): Boolean {
        val minecraftVersion = optionalProperty(project, "project.minecraft")
        return minecraftVersion != null && VersionPolicy.useUnobfuscatedMinecraft(minecraftVersion)
    }
}
