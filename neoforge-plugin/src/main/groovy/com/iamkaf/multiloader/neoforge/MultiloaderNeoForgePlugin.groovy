package com.iamkaf.multiloader.neoforge

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderNeoForgePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
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
                    systemProperty('neoforge.enabledGameTestNamespaces', ConventionSupport.requiredProperty(project, 'mod_id'))
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
                "${ConventionSupport.requiredProperty(project, 'mod_id')}" {
                    sourceSet project.sourceSets.main
                }
            }
        }
    }
}
