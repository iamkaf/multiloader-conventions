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
        def modId = requiredProp('mod.id')
        def modName = requiredProp('mod.name')
        def loader = requiredProp('loader')
        def accessWidener = project.rootProject.file("common/src/main/resources/${modId}.accesswidener")
        def commonProject = project.project(":common:${minecraftVersion}")
        def commonGeneratedJavaDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def commonGeneratedResourcesDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def generatedJavaDir = project.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def generatedResourcesDir = project.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def versionDir = project.rootProject.file("versions/${minecraftVersion}")
        def loomPluginId = useUnobfuscatedMinecraft ? 'net.fabricmc.fabric-loom' : 'fabric-loom'

        project.pluginManager.apply(loomPluginId)

        project.group = requiredProp('project.group')
        project.version = requiredProp('project.version')

        project.extensions.configure(BasePluginExtension) { base ->
            base.archivesName = "${modId}-${loader}"
        }

        project.ext.sharedRepositories.call(project)

        project.sourceSets {
            main {
                java.srcDirs = [
                    commonGeneratedJavaDir.get().asFile,
                    generatedJavaDir.get().asFile,
                ]
                resources.srcDirs = [
                    versionDir.toPath().resolve('common/src/main/resources').toFile(),
                    versionDir.toPath().resolve('fabric/src/main/resources').toFile(),
                    commonGeneratedResourcesDir.get().asFile,
                    generatedResourcesDir.get().asFile,
                    project.rootProject.file('src/main/generated'),
                ]
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

        ['compileJava', 'processResources', 'sourcesJar', 'javadoc'].each { taskName ->
            project.tasks.named(taskName).configure {
                dependsOn commonProject.tasks.named('stonecutterGenerate')
                dependsOn project.tasks.named('stonecutterGenerate')
            }
        }

        project.dependencies {
            compileOnly library('mixin')
            compileOnly library('mixin-extras')
            annotationProcessor library('mixin-extras')
            implementation library('gson')

            minecraft library('minecraft')

            if (!useUnobfuscatedMinecraft) {
                mappings project.loom.layered {
                    officialMojangMappings()
                    parchment library('parchment')
                }
                modImplementation library('fabric-loader')
            } else {
                implementation library('fabric-loader')
            }

            if (useUnobfuscatedMinecraft) {
                implementation library('fabric-api')
                implementation library('modmenu')
            } else {
                modImplementation library('fabric-api')
                modImplementation library('modmenu')
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
