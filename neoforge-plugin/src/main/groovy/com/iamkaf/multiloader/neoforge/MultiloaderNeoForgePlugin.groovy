package com.iamkaf.multiloader.neoforge

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

class MultiloaderNeoForgePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!isStonecutterNeoForgeProject(project)) {
            applyFlatNeoForgePlugin(project)
            return
        }

        applyStonecutterNeoForgePlugin(project)
    }

    private static void applyFlatNeoForgePlugin(Project project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin)
        project.pluginManager.apply('net.neoforged.moddev')

        project.extensions.configure('neoForge') { neoForge ->
            neoForge.version = ConventionSupport.versionAlias(project, 'neoforge')

            def accessTransformerFile = ConventionSupport.commonFile(project, 'src/main/resources/META-INF/accesstransformer.cfg')
            if (accessTransformerFile.exists()) {
                neoForge.accessTransformers.from(accessTransformerFile.absolutePath)
            }

            neoForge.parchment {
                minecraftVersion = ConventionSupport.optionalVersionAlias(project, 'parchment-minecraft')
                mappingsVersion = ConventionSupport.requiredLibrary(project, 'parchment').versionConstraint.requiredVersion
            }

            neoForge.runs {
                configureEach {
                    systemProperty('neoforge.enabledGameTestNamespaces', ConventionSupport.requiredProperty(project, 'mod.id'))
                    ideName = "NeoForge ${it.name.capitalize()} (${project.path})"
                }
                client {
                    client()
                }
                data {
                    client()
                }
                server {
                    server()
                }
            }

            neoForge.mods {
                "${ConventionSupport.requiredProperty(project, 'mod.id')}" {
                    sourceSet project.sourceSets.main
                }
            }
        }
    }

    private static void applyStonecutterNeoForgePlugin(Project project) {
        project.pluginManager.apply('com.iamkaf.multiloader.core')
        project.pluginManager.apply('java-library')
        project.pluginManager.apply('maven-publish')

        def requiredProp = { String name -> project.ext.requiredProp.call(project, name) }
        def optionalProp = { String name -> project.ext.optionalProp.call(project, name) }
        def minecraftVersion = requiredProp('project.minecraft')
        def useNeoGradleUserdev = usesNeoGradleUserdev(minecraftVersion)
        project.pluginManager.apply(useNeoGradleUserdev ? 'net.neoforged.gradle.userdev' : 'net.neoforged.moddev')
        def catalog = project.ext.catalogFor.call(project, minecraftVersion) as VersionCatalog
        def libraryVersion = { String alias -> project.ext.versionOrNull.call(catalog, alias) }
        def hasParchment = libraryVersion('parchment') != null
        def modId = requiredProp('mod.id')
        def modName = requiredProp('mod.name')
        def loader = requiredProp('loader')
        def teaKitVersion = libraryVersion('teakit')
        def teaKitLibrary = catalog.findLibrary('teakit-neoforge')
        def hasTeaKit = teaKitLibrary.present && teaKitVersion != null && teaKitVersion != 'null'
        def useTeaKit = project.providers.systemProperty("${modId}.withTeaKit")
            .orElse(project.providers.gradleProperty("${modId}.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get()
        def accessTransformerFile = project.rootProject.file('common/src/main/resources/META-INF/accesstransformer.cfg')
        def commonProject = project.project(":common:${minecraftVersion}")
        def commonGeneratedJavaDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def commonGeneratedResourcesDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def generatedJavaDir = project.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def generatedResourcesDir = project.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def mergedJavaDir = project.layout.buildDirectory.dir('generated/merged/main/java')
        def mergedResourcesDir = project.layout.buildDirectory.dir('generated/merged/main/resources')
        def versionDir = project.rootProject.file("versions/${minecraftVersion}")

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
            def versionLoaderJavaDir = versionDir.toPath().resolve('neoforge/src/main/java').toFile()
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
            def versionLoaderResourcesDir = versionDir.toPath().resolve('neoforge/src/main/resources').toFile()
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
            compileOnly project.ext.library.call(catalog, 'mixin')
            compileOnly project.ext.library.call(catalog, 'mixin-extras')
            compileOnly 'org.jetbrains:annotations:24.1.0'
            annotationProcessor project.ext.library.call(catalog, 'mixin-extras')
            implementation project.ext.library.call(catalog, 'gson')
            if (useTeaKit && hasTeaKit) {
                runtimeOnly teaKitLibrary.get()
            }
        }

        if (useNeoGradleUserdev) {
            project.configurations {
                runtimeClasspath.extendsFrom(localRuntime)
            }

            project.dependencies {
                implementation "net.neoforged:neoforge:${libraryVersion('neoforge')}"
            }

            project.runs {
                configureEach {
                    systemProperty 'forge.logging.console.level', 'debug'
                    systemProperty 'neoforge.enabledGameTestNamespaces', modId
                    environmentVariables.put('MOD_CLASSES', '{source_roots}')
                    environmentVariables.put('MCP_MAPPINGS', '{mcp_mappings}')
                    modSource project.sourceSets.main
                }
                client {
                }
                data {
                    arguments.addAll(
                            '--mod', modId,
                            '--all',
                            '--output', project.file('src/generated/resources').absolutePath,
                            '--existing', project.file('src/main/resources').absolutePath
                    )
                }
                server {
                    argument '--nogui'
                }
            }
        } else {
            project.extensions.configure('neoForge') { neoForge ->
                neoForge.version = libraryVersion('neoforge')

                if (accessTransformerFile.exists()) {
                    neoForge.accessTransformers.from(accessTransformerFile.absolutePath)
                }

                if (hasParchment) {
                    neoForge.parchment {
                        setMinecraftVersion(libraryVersion('parchment-minecraft') ?: minecraftVersion)
                        setMappingsVersion(libraryVersion('parchment'))
                    }
                }

                neoForge.runs {
                    configureEach {
                        systemProperty('neoforge.enabledGameTestNamespaces', modId)
                        ideName = "NeoForge ${it.name.capitalize()} (${project.path})"
                    }
                    client {
                        client()
                    }
                    data {
                        client()
                    }
                    server {
                        server()
                    }
                }

                neoForge.mods {
                    "${modId}" {
                        sourceSet project.sourceSets.main
                    }
                }
            }
        }

        def expandProps = project.ext.expandProps.call(project, minecraftVersion, loader, catalog)
        def jsonExpandProps = expandProps.collectEntries { key, value ->
            [(key): value instanceof String ? value.replace('\n', '\\\\n') : value]
        }

        project.tasks.named('processResources', ProcessResources).configure {
            inputs.properties(expandProps.findAll { _, value -> value != null })

            filesMatching(['META-INF/neoforge.mods.toml', 'META-INF/mods.toml']) {
                expand(expandProps)
            }

            filesMatching(['pack.mcmeta', '*.mixins.json']) {
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

    private static boolean isStonecutterNeoForgeProject(Project project) {
        project.parent?.name == 'neoforge'
    }

    private static boolean usesNeoGradleUserdev(String minecraftVersion) {
        minecraftVersion in ['1.20.2', '1.20.3', '1.20.4']
    }
}
