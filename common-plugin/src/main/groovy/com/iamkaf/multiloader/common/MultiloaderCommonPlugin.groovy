package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
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
        def useLegacyForgeCommon = minecraftVersion == '1.20.1'
        def accessTransformerFile = resolveAccessTransformerFile(project)
        def usesStonecutter = project.tasks.findByName('stonecutterGenerate') != null

        project.pluginManager.apply(useLegacyForgeCommon ? 'net.neoforged.moddev.legacyforge' : 'net.neoforged.moddev')

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

        if (useLegacyForgeCommon) {
            project.legacyForge {
                mcpVersion = versionOrNull('minecraft')

                if (accessTransformerFile.exists()) {
                    accessTransformers = [accessTransformerFile.absolutePath]
                }

                parchment {
                    setMinecraftVersion(versionOrNull('parchment-minecraft') ?: versionOrNull('minecraft'))
                    setMappingsVersion(versionOrNull('parchment'))
                }
            }
        } else {
            project.neoForge {
                neoFormVersion = versionOrNull('neoform')

                if (accessTransformerFile.exists()) {
                    accessTransformers.from(accessTransformerFile.absolutePath)
                }

                parchment {
                    setMinecraftVersion(versionOrNull('parchment-minecraft') ?: versionOrNull('minecraft'))
                    setMappingsVersion(versionOrNull('parchment'))
                }
            }
        }

        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
        }

        project.tasks.withType(ProcessResources).configureEach {
            exclude('.cache/**')
        }

        project.tasks.withType(Jar).configureEach {
            exclude('.cache/**')
        }

        project.tasks.named('sourcesJar', Jar).configure {
            duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        }

        if (usesStonecutter) {
            ['compileJava', 'processResources', 'sourcesJar', 'javadoc'].each { taskName ->
                project.tasks.named(taskName).configure {
                    dependsOn project.tasks.named('stonecutterGenerate')
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
            compileOnly library('mixin')
            compileOnly library('mixin-extras')
            annotationProcessor library('mixin-extras')
            implementation library('gson')
        }

        project.extensions.configure(PublishingExtension) { publishing ->
            publishing.publications {
                mavenJava(MavenPublication) {
                    from project.components.java
                    artifactId = project.base.archivesName.get()
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

                main.java.srcDirs = [generatedJavaDir.get().asFile]
                main.resources.srcDirs = [
                    versionDir.toPath().resolve('common/src/main/resources').toFile(),
                    generatedResourcesDir.get().asFile,
                ]
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
