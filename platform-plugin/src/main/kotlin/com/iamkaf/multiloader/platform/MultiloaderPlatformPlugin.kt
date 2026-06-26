package com.iamkaf.multiloader.platform

import com.iamkaf.multiloader.common.MultiloaderCommonPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderPlatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(MultiloaderCommonPlugin::class.java)
        ConventionSupport.configureLoaderBridge(project)
    }
}
