package com.iamkaf.multiloader.platform

import com.iamkaf.multiloader.common.MultiloaderCommonPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderPlatformPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply(MultiloaderCommonPlugin)
        ConventionSupport.configureLoaderBridge(project)
    }
}
