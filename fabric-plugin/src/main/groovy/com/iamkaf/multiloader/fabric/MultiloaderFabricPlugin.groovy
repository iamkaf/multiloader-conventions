package com.iamkaf.multiloader.fabric

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderFabricPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin)
        project.pluginManager.apply('fabric-loom')
        project.pluginManager.apply('com.hypherionmc.modutils.modpublisher')

        project.extensions.configure('loom') { loom ->
            def accessWidener = ConventionSupport.commonFile(project, "src/main/resources/${ConventionSupport.requiredProperty(project, 'mod_id')}.accesswidener")
            if (accessWidener.exists()) {
                loom.accessWidenerPath.set(accessWidener)
            }

            loom.mixin {
                defaultRefmapName.set("${ConventionSupport.requiredProperty(project, 'mod_id')}.refmap.json")
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

        ConventionSupport.configurePublisherForLoader(project, 'fabric')
    }
}
