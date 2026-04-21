package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources

class MultiloaderCommonPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!isCommonProject(project)) {
            ConventionSupport.configureCommonProject(project)
            return
        }

        project.pluginManager.apply('com.iamkaf.multiloader.core')
        project.pluginManager.apply('java-library')
        project.pluginManager.apply('maven-publish')

        def requiredProp = { String name -> project.ext.requiredProp.call(project, name) }
        def minecraftVersion = requiredProp('project.minecraft')
        def catalog = project.ext.catalogFor.call(project, minecraftVersion) as VersionCatalog
        def library = { String alias -> project.ext.library.call(catalog, alias) }
        def versionOrNull = { String alias -> project.ext.versionOrNull.call(catalog, alias) }
        def modId = requiredProp('mod.id')
        def useFabricLoomCommon = minecraftVersion == '1.16.5' ||
            minecraftVersion.startsWith('1.16.') ||
            minecraftVersion == '1.16' ||
            minecraftVersion.startsWith('1.15.') ||
            minecraftVersion == '1.15' ||
            minecraftVersion.startsWith('1.14.')
        def useLegacyForgeCommon = !useFabricLoomCommon && versionOrNull('neoform') == null
        def hasParchment = versionOrNull('parchment') != null
        def accessTransformerFile = resolveAccessTransformerFile(project)
        def usesStonecutter = project.tasks.findByName('stonecutterGenerate') != null

        if (useFabricLoomCommon) {
            project.pluginManager.apply('fabric-loom')
        } else {
            project.pluginManager.apply(useLegacyForgeCommon ? 'net.neoforged.moddev.legacyforge' : 'net.neoforged.moddev')
        }

        project.group = requiredProp('project.group')
        project.version = requiredProp('project.version')

        project.extensions.configure(BasePluginExtension) { base ->
            base.archivesName = "${modId}-common"
        }

        project.ext.sharedRepositories.call(project)

        configureSourceSets(project, minecraftVersion, usesStonecutter)

        project.java {
            withSourcesJar()
            withJavadocJar()
            toolchain.languageVersion = JavaLanguageVersion.of(requiredProp('project.java').toInteger())
        }

        if (useFabricLoomCommon) {
            project.dependencies {
                minecraft library('minecraft')
                if (hasParchment) {
                    mappings project.loom.layered {
                        officialMojangMappings()
                        parchment library('parchment')
                    }
                } else {
                    mappings project.loom.officialMojangMappings()
                }
                modImplementation library('fabric-loader')
            }
        } else if (useLegacyForgeCommon) {
            project.legacyForge {
                mcpVersion = versionOrNull('minecraft')

                if (accessTransformerFile.exists()) {
                    accessTransformers = [accessTransformerFile.absolutePath]
                }

                if (hasParchment) {
                    parchment {
                        setMinecraftVersion(versionOrNull('parchment-minecraft') ?: versionOrNull('minecraft'))
                        setMappingsVersion(versionOrNull('parchment'))
                    }
                }
            }
        } else {
            project.neoForge {
                neoFormVersion = versionOrNull('neoform')

                if (accessTransformerFile.exists()) {
                    accessTransformers.from(accessTransformerFile.absolutePath)
                }

                if (hasParchment) {
                    parchment {
                        setMinecraftVersion(versionOrNull('parchment-minecraft') ?: versionOrNull('minecraft'))
                        setMappingsVersion(versionOrNull('parchment'))
                    }
                }
            }
        }

        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
        }

        project.tasks.withType(ProcessResources).configureEach {
            duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
            exclude('.cache/**')
        }

        project.tasks.withType(Jar).configureEach {
            exclude('.cache/**')
        }

        project.tasks.named('sourcesJar', Jar).configure {
            duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        }

        if (usesStonecutter) {
            ['compileJava', 'sourcesJar', 'javadoc'].each { taskName ->
                project.tasks.named(taskName).configure {
                    dependsOn project.tasks.named('stonecutterGenerate')
                    dependsOn project.tasks.named('stageMergedJavaSources')
                }
            }
            ['processResources', 'sourcesJar'].each { taskName ->
                project.tasks.named(taskName).configure {
                    dependsOn project.tasks.named('stageMergedResources')
                }
            }
        }

        def commonJava = project.configurations.create('commonJava') {
            canBeResolved = false
            canBeConsumed = true
        }
        def commonResources = project.configurations.create('commonResources') {
            canBeResolved = false
            canBeConsumed = true
        }

        project.afterEvaluate {
            project.artifacts {
                project.sourceSets.main.java.sourceDirectories.files.each {
                    add(commonJava.name, it)
                }
                project.sourceSets.main.resources.sourceDirectories.files.each {
                    add(commonResources.name, it)
                }
            }
        }

        project.dependencies {
            compileOnly 'org.jetbrains:annotations:24.1.0'
            compileOnly library('mixin')
            compileOnly library('mixin-extras')
            annotationProcessor library('mixin-extras')
            implementation library('gson')
        }

        project.extensions.configure(PublishingExtension) { publishing ->
            publishing.publications {
                mavenJava(MavenPublication) {
                    artifactId = project.base.archivesName.get()
                    if (useFabricLoomCommon) {
                        artifact(project.tasks.named('remapJar')) {
                            builtBy(project.tasks.named('remapJar'))
                        }
                        if (project.tasks.findByName('remapSourcesJar') != null) {
                            artifact(project.tasks.named('remapSourcesJar')) {
                                builtBy(project.tasks.named('remapSourcesJar'))
                            }
                        } else {
                            artifact(project.tasks.named('sourcesJar')) {
                                builtBy(project.tasks.named('sourcesJar'))
                            }
                        }
                        artifact(project.tasks.named('javadocJar')) {
                            builtBy(project.tasks.named('javadocJar'))
                        }
                    } else {
                        from project.components.java
                    }
                }
            }
        }

        ['apiElements', 'runtimeElements', 'sourcesElements', 'javadocElements'].each { variant ->
            project.configurations.named(variant).configure { configuration ->
                configuration.outgoing.capability("${project.group}:${project.base.archivesName.get()}:${project.version}")
                configuration.outgoing.capability("${project.group}:${requiredProp('mod.id')}:${project.version}")
            }
        }

        project.ext.publishingRepositories.call(project.extensions.getByType(PublishingExtension), project.version.toString())
    }

    private static boolean isCommonProject(Project project) {
        project.name == 'common' || project.parent?.name == 'common'
    }

    private static void configureSourceSets(Project project, String minecraftVersion, boolean usesStonecutter) {
        def versionDir = project.rootProject.file("versions/${minecraftVersion}")
        def sourceSets = project.extensions.getByType(SourceSetContainer)

        sourceSets.named('main') { main ->
            if (usesStonecutter) {
                def generatedJavaDir = project.layout.buildDirectory.dir('generated/stonecutter/main/java')
                def generatedResourcesDir = project.layout.buildDirectory.dir('generated/stonecutter/main/resources')
                def mergedJavaDir = project.layout.buildDirectory.dir('generated/merged/main/java')
                def mergedResourcesDir = project.layout.buildDirectory.dir('generated/merged/main/resources')

                project.tasks.register('stageMergedJavaSources', Sync) { task ->
                    task.duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
                    task.dependsOn(project.tasks.named('stonecutterGenerate'))
                    task.from(generatedJavaDir)
                    def versionJavaDir = versionDir.toPath().resolve('common/src/main/java').toFile()
                    if (versionJavaDir.isDirectory()) {
                        task.from(versionJavaDir)
                    }
                    task.into(mergedJavaDir)
                }

                project.tasks.register('stageMergedResources', Sync) { task ->
                    task.duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
                    task.dependsOn(project.tasks.named('stonecutterGenerate'))
                    task.from(generatedResourcesDir)
                    task.from(project.rootProject.file('common/src/main/generated'))
                    task.from(project.rootProject.file('src/main/generated'))
                    def versionResourcesDir = versionDir.toPath().resolve('common/src/main/resources').toFile()
                    if (versionResourcesDir.isDirectory()) {
                        task.from(versionResourcesDir)
                    }
                    task.into(mergedResourcesDir)
                }

                main.java.srcDirs = [mergedJavaDir.get().asFile]
                main.resources.srcDirs = [mergedResourcesDir.get().asFile]
                return
            }

            main.resources.srcDir(project.file('src/main/generated'))
        }
    }

    private static File resolveAccessTransformerFile(Project project) {
        if (project.parent?.name == 'common') {
            return project.rootProject.file('common/src/main/resources/META-INF/accesstransformer.cfg')
        }

        return project.file('src/main/resources/META-INF/accesstransformer.cfg')
    }
}
