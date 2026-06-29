package com.iamkaf.multiloader.platform

import com.iamkaf.multiloader.common.MultiloaderCommonPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import com.iamkaf.multiloader.support.ConsumerDslPolicy
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderPlatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        ConsumerDslPolicy.requireKotlinDsl(project)
        project.pluginManager.apply(MultiloaderCommonPlugin::class.java)
        ConventionSupport.configureLoaderBridge(project)
    }
}
