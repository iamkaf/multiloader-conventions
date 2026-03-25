package com.iamkaf.multiloader.forge

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

class MultiloaderForgePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin)
        project.pluginManager.apply('net.minecraftforge.gradle')
        def usesLegacyUserdev = ConventionSupport.versionAlias(project, 'minecraft') == '1.16.5'
        def usesUnobfuscatedMinecraft = ConventionSupport.isUnobfuscatedMinecraft(project)

        def mixinConfigs = ConventionSupport.collectMixinConfigs(project, 'forge')
        project.tasks.named('jar', Jar).configure { task ->
            if (!mixinConfigs.isEmpty()) {
                task.manifest.attributes(['MixinConfigs': mixinConfigs.join(',')])
            }
        }

        project.extensions.configure('minecraft') { minecraft ->
            if (usesLegacyUserdev) {
                minecraft.mappings(channel: 'official', version: ConventionSupport.versionAlias(project, 'minecraft'))
                minecraft.copyIdeResources = true
                minecraft.reobf = false
            }

            def accessTransformerFile = project.file('src/main/resources/META-INF/accesstransformer.cfg')
            if (accessTransformerFile.exists()) {
                minecraft.accessTransformer = accessTransformerFile
            }

            minecraft.runs {
                if (usesLegacyUserdev) {
                    configureEach {
                        property 'forge.logging.console.level', 'debug'
                        mods {
                            "${ConventionSupport.requiredProperty(project, 'mod.id')}" {
                                source project.extensions.getByType(SourceSetContainer).named('main').get()
                            }
                        }
                    }

                    client {
                        workingDirectory project.file('runs/client')
                        ideaModule "${project.rootProject.name}.${project.name}.main"
                        taskName 'runClient'
                    }

                    server {
                        workingDirectory project.file('runs/server')
                        ideaModule "${project.rootProject.name}.${project.name}.main"
                        taskName 'runServer'
                        args '--nogui'
                    }
                } else {
                    configureEach {
                        workingDir.convention(project.layout.projectDirectory.dir('run'))
                        mixinConfigs.each { mixinConfig ->
                            args '--mixin.config', mixinConfig
                        }
                    }

                    register('client') {
                    }

                    register('server') {
                        args '--nogui'
                    }
                }
            }
        }

        if (usesUnobfuscatedMinecraft) {
            project.repositories {
                if (project.minecraft.metaClass.respondsTo(project.minecraft, 'mavenizer', Object)) {
                    project.minecraft.mavenizer(it)
                }
                if (project.fg.hasProperty('forgeMaven')) {
                    maven project.fg.forgeMaven
                }
                if (project.fg.hasProperty('minecraftLibsMaven')) {
                    maven project.fg.minecraftLibsMaven
                }
            }
        }

        if (usesLegacyUserdev || !usesUnobfuscatedMinecraft) {
            project.dependencies {
                implementation project.minecraft.dependency(
                    "net.minecraftforge:forge:${ConventionSupport.versionAlias(project, 'minecraft')}-${ConventionSupport.versionAlias(project, 'forge')}"
                )
                implementation('net.sf.jopt-simple:jopt-simple:5.0.4') {
                    version {
                        strictly '5.0.4'
                    }
                }
            }
        } else {
            project.dependencies {
                implementation project.minecraft.dependency("net.minecraftforge:forge:${ConventionSupport.versionAlias(project, 'forge')}")
                implementation('net.sf.jopt-simple:jopt-simple:5.0.4') {
                    version {
                        strictly '5.0.4'
                    }
                }
            }
        }

        project.extensions.getByType(SourceSetContainer).configureEach { sourceSet ->
            def dir = project.layout.buildDirectory.dir("sourcesSets/$sourceSet.name")
            sourceSet.output.resourcesDir = dir
            sourceSet.java.destinationDirectory = dir
        }
    }
}
