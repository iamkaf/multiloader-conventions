package com.iamkaf.multiloader.common

import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderCommonPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        ConventionSupport.configureCommonProject(project)
    }
}
