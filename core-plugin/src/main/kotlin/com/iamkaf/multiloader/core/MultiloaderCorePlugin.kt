package com.iamkaf.multiloader.core

import com.iamkaf.multiloader.support.ConsumerDslPolicy
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderCorePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        ConsumerDslPolicy.requireKotlinDsl(project)
        // Marker plugin kept for stable public plugin resolution.
    }
}
