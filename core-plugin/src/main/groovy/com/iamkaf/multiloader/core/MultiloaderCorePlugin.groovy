package com.iamkaf.multiloader.core

import com.iamkaf.multiloader.common.MultiloaderCommonPlugin
import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderCorePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply(MultiloaderCommonPlugin)

        if (project.name in ConventionSupport.LOADER_PROJECTS) {
            project.pluginManager.apply(MultiloaderPlatformPlugin)
        }
    }
}
