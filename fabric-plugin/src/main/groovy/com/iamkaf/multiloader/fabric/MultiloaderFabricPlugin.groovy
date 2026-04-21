package com.iamkaf.multiloader.fabric

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources

class MultiloaderFabricPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!isStonecutterFabricProject(project)) {
            applyFlatFabricPlugin(project)
            return
        }

        applyStonecutterFabricPlugin(project)
    }

    private static void applyFlatFabricPlugin(Project project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin)
        def loomPluginId = ConventionSupport.isUnobfuscatedMinecraft(project)
            ? 'net.fabricmc.fabric-loom'
            : 'fabric-loom'
        project.pluginManager.apply(loomPluginId)

        project.pluginManager.withPlugin(loomPluginId) {
            ConventionSupport.configureFabricBaseDependencies(project)
            ConventionSupport.registerFabricDatagenHelper(project)

            project.extensions.configure('loom') { loom ->
                def accessWidener = ConventionSupport.commonFile(project, "src/main/resources/${ConventionSupport.requiredProperty(project, 'mod.id')}.accesswidener")
                if (accessWidener.exists()) {
                    loom.accessWidenerPath.set(accessWidener)
                }

                loom.mixin {
                    defaultRefmapName.set("${ConventionSupport.requiredProperty(project, 'mod.id')}.refmap.json")
                }

                loom.runs {
                    client {
                        client()
                        setConfigName('Fabric Client')
                        ideConfigGenerated(true)
                        runDir('runs/client')
                    }
                    server {
                        server()
                        setConfigName('Fabric Server')
                        ideConfigGenerated(true)
                        runDir('runs/server')
                    }
                }
            }
        }
    }

    private static void applyStonecutterFabricPlugin(Project project) {
        project.pluginManager.apply('com.iamkaf.multiloader.core')
        project.pluginManager.apply('java-library')
        project.pluginManager.apply('maven-publish')

        def requiredProp = { String name -> project.ext.requiredProp.call(project, name) }
        def optionalProp = { String name -> project.ext.optionalProp.call(project, name) }
        def minecraftVersion = requiredProp('project.minecraft')
        def catalog = project.ext.catalogFor.call(project, minecraftVersion) as VersionCatalog
        def library = { String alias -> project.ext.library.call(catalog, alias) }
        def useUnobfuscatedMinecraft = project.ext.useUnobfuscatedMinecraft.call(minecraftVersion)
        def useLegacyFabricApiModules = minecraftVersion == '1.16' || minecraftVersion == '1.16.1'
        def hasParchment = project.ext.versionOrNull.call(catalog, 'parchment') != null
        def modMenuVersion = project.ext.versionOrNull.call(catalog, 'modmenu')
        def hasModMenu = modMenuVersion != null && modMenuVersion != 'null'
        def modId = requiredProp('mod.id')
        def modName = requiredProp('mod.name')
        def loader = requiredProp('loader')
        def teaKitVersion = project.ext.versionOrNull.call(catalog, 'teakit')
        def teaKitLibrary = catalog.findLibrary('teakit-fabric')
        def hasTeaKit = teaKitLibrary.present && teaKitVersion != null && teaKitVersion != 'null'
        def useTeaKit = project.providers.systemProperty("${modId}.withTeaKit")
            .orElse(project.providers.gradleProperty("${modId}.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get()
        def useModernFabricRuntime = !minecraftVersion.startsWith('1.')
        def commonProject = project.project(":common:${minecraftVersion}")
        def commonGeneratedJavaDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def commonGeneratedResourcesDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def generatedJavaDir = project.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def generatedResourcesDir = project.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def mergedJavaDir = project.layout.buildDirectory.dir('generated/merged/main/java')
        def mergedResourcesDir = project.layout.buildDirectory.dir('generated/merged/main/resources')
        def versionDir = project.rootProject.file("versions/${minecraftVersion}")
        def versionAccessWidener = versionDir.toPath().resolve("common/src/main/resources/${modId}.accesswidener").toFile()
        def accessWidener = versionAccessWidener.exists()
            ? versionAccessWidener
            : project.rootProject.file("common/src/main/resources/${modId}.accesswidener")
        def loomPluginId = useUnobfuscatedMinecraft ? 'net.fabricmc.fabric-loom' : 'fabric-loom'

        project.pluginManager.apply(loomPluginId)

        project.group = requiredProp('project.group')
        project.version = requiredProp('project.version')

        project.extensions.configure(BasePluginExtension) { base ->
            base.archivesName = "${modId}-${loader}"
        }

        project.ext.sharedRepositories.call(project)

        project.tasks.register('stageMergedJavaSources', Sync) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.dependsOn(commonProject.tasks.named('stonecutterGenerate'))
            task.dependsOn(project.tasks.named('stonecutterGenerate'))
            task.from(commonGeneratedJavaDir)
            task.from(generatedJavaDir)
            def versionCommonJavaDir = versionDir.toPath().resolve('common/src/main/java').toFile()
            if (versionCommonJavaDir.isDirectory()) {
                task.from(versionCommonJavaDir)
            }
            def versionLoaderJavaDir = versionDir.toPath().resolve('fabric/src/main/java').toFile()
            if (versionLoaderJavaDir.isDirectory()) {
                task.from(versionLoaderJavaDir)
            }
            task.into(mergedJavaDir)
        }

        project.tasks.register('stageMergedResources', Sync) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.dependsOn(commonProject.tasks.named('stonecutterGenerate'))
            task.dependsOn(project.tasks.named('stonecutterGenerate'))
            task.from(commonGeneratedResourcesDir)
            task.from(generatedResourcesDir)
            task.from(project.rootProject.file('common/src/main/generated'))
            task.from(project.rootProject.file('src/main/generated'))
            def versionCommonResourcesDir = versionDir.toPath().resolve('common/src/main/resources').toFile()
            if (versionCommonResourcesDir.isDirectory()) {
                task.from(versionCommonResourcesDir)
            }
            def versionLoaderResourcesDir = versionDir.toPath().resolve('fabric/src/main/resources').toFile()
            if (versionLoaderResourcesDir.isDirectory()) {
                task.from(versionLoaderResourcesDir)
            }
            task.into(mergedResourcesDir)
        }

        project.sourceSets {
            main {
                java.srcDirs = [mergedJavaDir.get().asFile]
                resources.srcDirs = [mergedResourcesDir.get().asFile]
            }
        }

        project.java {
            withSourcesJar()
            withJavadocJar()
            toolchain.languageVersion = JavaLanguageVersion.of(requiredProp('project.java').toInteger())
        }

        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
        }

        project.tasks.withType(ProcessResources).configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude('.cache/**')
        }

        project.tasks.named('sourcesJar', Jar).configure {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude('.cache/**')
        }

        ['compileJava', 'sourcesJar', 'javadoc'].each { taskName ->
            project.tasks.named(taskName).configure {
                dependsOn commonProject.tasks.named('stonecutterGenerate')
                dependsOn project.tasks.named('stonecutterGenerate')
                dependsOn project.tasks.named('stageMergedJavaSources')
            }
        }
        ['processResources', 'sourcesJar'].each { taskName ->
            project.tasks.named(taskName).configure {
                dependsOn project.tasks.named('stageMergedResources')
            }
        }

        project.dependencies {
            compileOnly library('mixin')
            compileOnly library('mixin-extras')
            annotationProcessor library('mixin-extras')
            implementation library('gson')

            minecraft library('minecraft')

            if (!useUnobfuscatedMinecraft) {
                if (hasParchment) {
                    mappings project.loom.layered {
                        officialMojangMappings()
                        parchment library('parchment')
                    }
                } else {
                    mappings project.loom.officialMojangMappings()
                }
                if (minecraftVersion == '1.18.2') {
                    modImplementation('net.fabricmc:fabric-loader:0.14.9') {
                        version { strictly '0.14.9' }
                    }
                } else {
                    modImplementation library('fabric-loader')
                }
            } else {
                if (minecraftVersion == '1.18.2') {
                    implementation('net.fabricmc:fabric-loader:0.14.9') {
                        version { strictly '0.14.9' }
                    }
                } else {
                    implementation library('fabric-loader')
                }
            }

            if (useUnobfuscatedMinecraft) {
                if (useLegacyFabricApiModules) {
                    implementation 'net.fabricmc.fabric-api:fabric-api-base:0.4.0+3cc0f0907d'
                    implementation 'net.fabricmc.fabric-api:fabric-command-api-v1:1.0.9+6a2618f53a'
                    implementation 'net.fabricmc.fabric-api:fabric-networking-api-v1:1.0.5+3cc0f0907d'
                    implementation 'net.fabricmc.fabric-api:fabric-lifecycle-events-v1:1.2.2+3cc0f0907d'
                    implementation 'net.fabricmc.fabric-api:fabric-resource-loader-v0:0.4.2+ca58154a7d'
                } else {
                    implementation library('fabric-api')
                }
                if (hasModMenu) {
                    implementation library('modmenu')
                }
            } else {
                if (useLegacyFabricApiModules) {
                    modImplementation 'net.fabricmc.fabric-api:fabric-api-base:0.4.0+3cc0f0907d'
                    modImplementation 'net.fabricmc.fabric-api:fabric-command-api-v1:1.0.9+6a2618f53a'
                    modImplementation 'net.fabricmc.fabric-api:fabric-networking-api-v1:1.0.5+3cc0f0907d'
                    modImplementation 'net.fabricmc.fabric-api:fabric-lifecycle-events-v1:1.2.2+3cc0f0907d'
                    modImplementation 'net.fabricmc.fabric-api:fabric-resource-loader-v0:0.4.2+ca58154a7d'
                } else {
                    modImplementation library('fabric-api')
                }
                if (hasModMenu) {
                    modImplementation library('modmenu')
                }
            }

            if (useTeaKit && hasTeaKit) {
                if (useModernFabricRuntime) {
                    runtimeOnly teaKitLibrary.get()
                } else {
                    modLocalRuntime teaKitLibrary.get()
                }
            }
        }

        registerBranchDatagenHelper(project)

        project.extensions.configure('loom') { loom ->
            if (accessWidener.exists()) {
                loom.accessWidenerPath.set(accessWidener)
            }

            loom.mixin {
                defaultRefmapName.set("${modId}.refmap.json")
            }

            loom.runs {
                client {
                    client()
                    setConfigName('Fabric Client')
                    ideConfigGenerated(true)
                    runDir('runs/client')
                }
                server {
                    server()
                    setConfigName('Fabric Server')
                    ideConfigGenerated(true)
                    runDir('runs/server')
                }
            }
        }

        def expandProps = project.ext.expandProps.call(project, minecraftVersion, loader, catalog)
        def jsonExpandProps = expandProps.collectEntries { key, value ->
            [(key): value instanceof String ? value.replace('\n', '\\\\n') : value]
        }

        project.tasks.named('processResources', ProcessResources).configure {
            inputs.properties(expandProps.findAll { _, value -> value != null })

            filesMatching(['pack.mcmeta', 'fabric.mod.json', '*.mixins.json']) {
                expand(jsonExpandProps)
            }
        }

        project.tasks.named('jar', Jar).configure {
            manifest.attributes([
                'Specification-Title'   : modName,
                'Specification-Vendor'  : optionalProp('mod.authors'),
                'Specification-Version' : project.version.toString(),
                'Implementation-Title'  : loader,
                'Implementation-Version': project.version.toString(),
                'Implementation-Vendor' : optionalProp('mod.authors'),
                'Built-On-Minecraft'    : minecraftVersion,
                'Built-By'              : 'multiloader-conventions',
            ])
        }

        project.extensions.configure(PublishingExtension) { publishing ->
            publishing.publications {
                mavenJava(MavenPublication) {
                    from project.components.java
                    artifactId = project.base.archivesName.get()
                }
            }
        }

        project.ext.publishingRepositories.call(project.extensions.getByType(PublishingExtension), project.version.toString())
    }

    private static void registerBranchDatagenHelper(Project project) {
        project.extensions.extraProperties.set('enableCommonFabricDatagen', {
            project.extensions.configure('fabricApi') { fabricApi ->
                fabricApi.configureDataGeneration {
                    client = true
                    outputDirectory = project.rootProject.file('common/src/main/generated')
                }
            }
        })
    }

    private static boolean isStonecutterFabricProject(Project project) {
        project.parent?.name == 'fabric'
    }
}
